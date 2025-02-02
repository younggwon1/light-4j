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

package com.networknt.client.ssl;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.networknt.utility.StringUtils;

/**
 * This class holds configuration values related to server identify check.
 *
 * @author Daniel Zhao
 *
 */
public class TLSConfig {
    public static final String VERIFY_HOSTNAME="verifyHostname";

    private final boolean checkServerIdentify;

    private TLSConfig(boolean checkServerIdentify) {
    	this.checkServerIdentify = checkServerIdentify;
    }

    public boolean getCheckServerIdentity() {
    	return checkServerIdentify;
    }

}
