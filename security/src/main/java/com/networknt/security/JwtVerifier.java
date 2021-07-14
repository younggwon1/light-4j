/*
 * Copyright (c) 2016 Network New Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.networknt.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.networknt.client.oauth.OauthHelper;
import com.networknt.client.oauth.SignKeyRequest;
import com.networknt.client.oauth.TokenKeyRequest;
import com.networknt.config.Config;
import com.networknt.exception.ExpiredTokenException;
import com.networknt.status.Status;
import com.networknt.utility.FingerPrintUtil;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwt.consumer.*;
import org.jose4j.jwx.JsonWebStructure;
import org.jose4j.keys.resolvers.JwksVerificationKeyResolver;
import org.jose4j.keys.resolvers.VerificationKeyResolver;
import org.jose4j.keys.resolvers.X509VerificationKeyResolver;
import org.owasp.encoder.Encode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * This is a new class that is designed as non-static to replace the JwtHelper which is a static class. The reason
 * is to pass the framework specific security configuration so that we can eliminate the security.yml for token
 * verification.
 *
 * The JwtHelper will be stay for a while for backward compatibility reason as it is a public class and users might
 * use it in their application. The only thing that need to remember is to have both security.yml and openapi-security.yml
 * for the security configuration and there are overlap between these two files.
 *
 * To use this class, create an instance by passing in the security configuration and cache the instance in your app
 * as a field or an instance variable.
 *
 * @author Steve Hu
 */
public class JwtVerifier {
    static final Logger logger = LoggerFactory.getLogger(JwtVerifier.class);
    static final String GET_KEY_ERROR = "ERR10066";

    public static final String KID = "kid";
    public static final String SECURITY_CONFIG = "security";
    public static final String JWT_CONFIG = "jwt";
    public static final String JWT_CERTIFICATE = "certificate";
    public static final String JWT_CLOCK_SKEW_IN_SECONDS = "clockSkewInSeconds";
    public static final String ENABLE_VERIFY_JWT = "enableVerifyJwt";
    private static final String ENABLE_JWT_CACHE = "enableJwtCache";
    private static final String BOOTSTRAP_FROM_KEY_SERVICE = "bootstrapFromKeyService";
    private static final int CACHE_EXPIRED_IN_MINUTES = 15;

    public static final String JWT_KEY_RESOLVER = "keyResolver";
    public static final String JWT_KEY_RESOLVER_X509CERT = "X509Certificate";
    public static final String JWT_KEY_RESOLVER_JWKS = "JsonWebKeySet";


    Map<String, Object> config;
    Map<String, Object> jwtConfig;
    int secondsOfAllowedClockSkew;
    Boolean enableJwtCache;
    Boolean bootstrapFromKeyService;

    static Cache<String, JwtClaims> cache;
    static Map<String, X509Certificate> certMap;
    static Map<String, List<JsonWebKey>> jwksMap;
    static List<String> fingerPrints;

    public JwtVerifier(Map<String, Object> config) {
        this.config = config;
        this.jwtConfig = (Map)config.get(JWT_CONFIG);
        this.secondsOfAllowedClockSkew = (Integer)jwtConfig.get(JWT_CLOCK_SKEW_IN_SECONDS);
        this.bootstrapFromKeyService = (Boolean)config.get(BOOTSTRAP_FROM_KEY_SERVICE);
        this.enableJwtCache = (Boolean)config.get(ENABLE_JWT_CACHE);
        if(Boolean.TRUE.equals(enableJwtCache)) {
            cache = Caffeine.newBuilder()
                    // assuming that the clock screw time is less than 5 minutes
                    .expireAfterWrite(CACHE_EXPIRED_IN_MINUTES, TimeUnit.MINUTES)
                    .build();
        }
        switch ((String) jwtConfig.getOrDefault(JWT_KEY_RESOLVER, JWT_KEY_RESOLVER_X509CERT)) {
            // init getting JWK
            case JWT_KEY_RESOLVER_JWKS:
                jwksMap = new HashMap<>();
                List<JsonWebKey> jwkList = getJsonWebKeySetForToken();
                if (jwkList == null || jwkList.isEmpty()) {
                    throw new RuntimeException("cannot get JWK from OAuth server");
                }
                jwksMap.put(jwkList.get(0).getKeyId(), jwkList);
                logger.debug("Successfully cached JWK");
                break;
            case JWT_KEY_RESOLVER_X509CERT:
                // load local public key certificates only if bootstrapFromKeyService is false
                if(bootstrapFromKeyService == null || Boolean.FALSE.equals(bootstrapFromKeyService)) {
                    certMap = new HashMap<>();
                    fingerPrints = new ArrayList<>();
                    if (jwtConfig.get(JWT_CERTIFICATE)!=null) {
                        Map<String, Object> keyMap = (Map<String, Object>) jwtConfig.get(JWT_CERTIFICATE);
                        for(String kid: keyMap.keySet()) {
                            X509Certificate cert = null;
                            try {
                                cert = readCertificate((String)keyMap.get(kid));
                            } catch (Exception e) {
                                logger.error("Exception:", e);
                            }
                            certMap.put(kid, cert);
                            fingerPrints.add(FingerPrintUtil.getCertFingerPrint(cert));
                        }
                   }
                }
                break;
            default:
                logger.info("{} not found or not recognized in jwt config. Use {} as default {}",
                        JWT_KEY_RESOLVER, JWT_KEY_RESOLVER_X509CERT, JWT_KEY_RESOLVER);
        }
    }

    /**
     * Read certificate from a file and convert it into X509Certificate object
     *
     * @param filename certificate file name
     * @return X509Certificate object
     * @throws Exception Exception while reading certificate
     */
    public X509Certificate readCertificate(String filename)
            throws Exception {
        InputStream inStream = null;
        X509Certificate cert = null;
        try {
            inStream = Config.getInstance().getInputStreamFromFile(filename);
            if (inStream != null) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                cert = (X509Certificate) cf.generateCertificate(inStream);
            } else {
                logger.info("Certificate " + Encode.forJava(filename) + " not found.");
            }
        } catch (Exception e) {
            logger.error("Exception: ", e);
        } finally {
            if (inStream != null) {
                try {
                    inStream.close();
                } catch (IOException ioe) {
                    logger.error("Exception: ", ioe);
                }
            }
        }
        return cert;
    }

    /**
     * Parse the jwt token from Authorization header.
     *
     * @param authorization authorization header.
     * @return JWT token
     */
    public static String getJwtFromAuthorization(String authorization) {
        String jwt = null;
        if(authorization != null) {
            String[] parts = authorization.split(" ");
            if (parts.length == 2) {
                String scheme = parts[0];
                String credentials = parts[1];
                Pattern pattern = Pattern.compile("^Bearer$", Pattern.CASE_INSENSITIVE);
                if (pattern.matcher(scheme).matches()) {
                    jwt = credentials;
                }
            }
        }
        return jwt;
    }

    /**
     * This method is to keep backward compatible for those call without VerificationKeyResolver.
     * @param jwt JWT token
     * @param ignoreExpiry indicate if the expiry will be ignored
     * @param isToken indicate if the JWT is a token
     * @return JwtClaims
     * @throws InvalidJwtException throw when the token is invalid
     * @throws ExpiredTokenException throw when the token is expired
     */
    public JwtClaims verifyJwt(String jwt, boolean ignoreExpiry, boolean isToken) throws InvalidJwtException, ExpiredTokenException {
        return verifyJwt(jwt, ignoreExpiry, isToken, this::getKeyResolver);
    }

    /**
     * Verify JWT token format and signature. If ignoreExpiry is true, skip expiry verification, otherwise
     * verify the expiry before signature verification.
     *
     * In most cases, we need to verify the expiry of the jwt token. The only time we need to ignore expiry
     * verification is in SPA middleware handlers which need to verify csrf token in jwt against the csrf
     * token in the request header to renew the expired token.
     *
     * @param jwt String of Json web token
     * @param ignoreExpiry If true, don't verify if the token is expired.
     * @param isToken True if the jwt is an OAuth 2.0 access token
     * @param getKeyResolver How to get VerificationKeyResolver
     * @return JwtClaims object
     * @throws InvalidJwtException InvalidJwtException
     * @throws ExpiredTokenException ExpiredTokenException
     */
    public JwtClaims verifyJwt(String jwt, boolean ignoreExpiry, boolean isToken, BiFunction<String, Boolean, VerificationKeyResolver> getKeyResolver)
            throws InvalidJwtException, ExpiredTokenException {
        JwtClaims claims;

        if(Boolean.TRUE.equals(enableJwtCache)) {
            claims = cache.getIfPresent(jwt);
            if(claims != null) {
                if(!ignoreExpiry) {
                    try {
                        // if using our own client module, the jwt token should be renewed automatically
                        // and it will never expired here. However, we need to handle other clients.
                        if ((NumericDate.now().getValue() - secondsOfAllowedClockSkew) >= claims.getExpirationTime().getValue())
                        {
                            logger.info("Cached jwt token is expired!");
                            throw new ExpiredTokenException("Token is expired");
                        }
                    } catch (MalformedClaimException e) {
                        // This is cached token and it is impossible to have this exception
                        logger.error("MalformedClaimException:", e);
                    }
                }
                // this claims object is signature verified already
                return claims;
            }
        }

        JwtConsumer consumer = new JwtConsumerBuilder()
                .setSkipAllValidators()
                .setDisableRequireSignature()
                .setSkipSignatureVerification()
                .build();

        JwtContext jwtContext = consumer.process(jwt);
        claims = jwtContext.getJwtClaims();
        JsonWebStructure structure = jwtContext.getJoseObjects().get(0);
        // need this kid to load public key certificate for signature verification
        String kid = structure.getKeyIdHeaderValue();

        // so we do expiration check here manually as we have the claim already for kid
        // if ignoreExpiry is false, verify expiration of the token
        if(!ignoreExpiry) {
            try {
                if ((NumericDate.now().getValue() - secondsOfAllowedClockSkew) >= claims.getExpirationTime().getValue())
                {
                    logger.info("jwt token is expired!");
                    throw new ExpiredTokenException("Token is expired");
                }
            } catch (MalformedClaimException e) {
                logger.error("MalformedClaimException:", e);
                throw new InvalidJwtException("MalformedClaimException", new ErrorCodeValidator.Error(ErrorCodes.MALFORMED_CLAIM, "Invalid ExpirationTime Format"), e, jwtContext);
            }
        }

        consumer = new JwtConsumerBuilder()
                .setRequireExpirationTime()
                .setAllowedClockSkewInSeconds(315360000) // use seconds of 10 years to skip expiration validation as we need skip it in some cases.
                .setSkipDefaultAudienceValidation()
                .setVerificationKeyResolver(getKeyResolver.apply(kid, isToken))
                .build();

        // Validate the JWT and process it to the Claims
        jwtContext = consumer.process(jwt);
        claims = jwtContext.getJwtClaims();
        if(Boolean.TRUE.equals(enableJwtCache)) {
            cache.put(jwt, claims);
        }
        return claims;
    }

    /**
     * Get VerificationKeyResolver based on the kid and isToken indicator. For the implementation, we check
     * the jwk first and 509Certificate if the jwk cannot find the kid. Basically, we want to iterate all
     * the resolvers and find the right one with the kid.
     *
     * @param kid key id from the JWT token
     * @param isToken indicate if the resolver is for token verification or signing.
     * @return VerificationKeyResolver
     */
    private VerificationKeyResolver getKeyResolver(String kid, boolean isToken) {
        // try the X509 certificate first

        // get the public key certificate from the cache that is loaded from security.yml if it is not there,
        // go to OAuth2 server /oauth2/key endpoint to get the public key certificate with kid as parameter.
        X509Certificate certificate = certMap == null? null : certMap.get(kid);
        if(certificate == null) {
            certificate = isToken? getCertForToken(kid) : getCertForSign(kid);
            if(certMap == null) certMap = new HashMap<>();  // null if bootstrapFromKeyService is true
            certMap.put(kid, certificate);
        } else {
            logger.debug("Got raw certificate for kid: {} from local cache", kid);
        }
        if(certificate != null) {
            X509VerificationKeyResolver x509VerificationKeyResolver = new X509VerificationKeyResolver(certificate);
            x509VerificationKeyResolver.setTryAllOnNoThumbHeader(true);
            return x509VerificationKeyResolver;
        } else {
            // try jwk if kid cannot be found in the certificate map.
            List<JsonWebKey> jwkList = jwksMap.get(kid);
            if (jwkList == null) {
                jwkList = getJsonWebKeySetForToken();
                if (jwkList == null || jwkList.isEmpty()) {
                    throw new RuntimeException("no JWK for kid: " + kid);
                }
                jwksMap.put(jwkList.get(0).getKeyId(), jwkList);
                logger.info("Got Json web key set for kid: {} from Oauth server", kid);
            }
            logger.debug("Got Json web key set from local cache");
            return new JwksVerificationKeyResolver(jwkList);
        }
    }

    /**
     * Retrieve JWK set from oauth server
     *
     * @return {@link List} of {@link JsonWebKey}
     */
    private List<JsonWebKey> getJsonWebKeySetForToken() {
        // the jwk indicator will ensure that the kid is not concat to the uri for path parameter.
        // the kid is not needed to get JWK
        TokenKeyRequest keyRequest = new TokenKeyRequest(null, true);
        try {
            logger.debug("Getting Json Web Key list from {}", keyRequest.getServerUrl());
            String key = OauthHelper.getKey(keyRequest);
            logger.debug("Got Json Web Key list from {}", keyRequest.getServerUrl());
            return new JsonWebKeySet(key).getJsonWebKeys();
        } catch (Exception e) {
            logger.error("Exception: ", e);
            logger.error(new Status(GET_KEY_ERROR).toString());
            throw new RuntimeException(e);
        }
    }

    public X509Certificate getCertForToken(String kid) {
        X509Certificate certificate = null;
        TokenKeyRequest keyRequest = new TokenKeyRequest(kid);
        try {
            logger.warn("<Deprecated: use JsonWebKeySet instead> Getting raw certificate for kid: {} from {}", kid, keyRequest.getServerUrl());
            String key = OauthHelper.getKey(keyRequest);
            logger.warn("<Deprecated: use JsonWebKeySet instead> Got raw certificate {} for kid: {}", key, kid);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            certificate = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(key.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            logger.error("Exception: ", e);
            throw new RuntimeException(e);
        }
        return certificate;
    }

    public X509Certificate getCertForSign(String kid) {
        X509Certificate certificate = null;
        SignKeyRequest keyRequest = new SignKeyRequest(kid);
        try {
            String key = OauthHelper.getKey(keyRequest);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            certificate = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(key.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            logger.error("Exception: ", e);
            throw new RuntimeException(e);
        }
        return certificate;
    }

    /**
     * Get a list of certificate fingerprints for server info endpoint so that certification process in light-portal
     * can detect if your service still use the default public key certificates provided by the light-4j framework.
     *
     * The default public key certificates are for dev only and should be replaced on any other environment or
     * set bootstrapFromKeyService: true if you are using light-oauth2 so that key can be dynamically loaded.
     *
     * @return List of certificate fingerprints
     */
    public List getFingerPrints() {
        return fingerPrints;
    }

}
