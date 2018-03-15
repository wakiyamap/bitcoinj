/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.monacoinj.params;

import org.monacoinj.core.Utils;

import static com.google.common.base.Preconditions.checkState;

/**
 * Parameters for the old version 2 testnet. This is not useful to you - it exists only because some unit tests are
 * based on it.
 */
public class TestNet2Params extends AbstractMonacoinNetParams {
    public static final int TESTNET_MAJORITY_WINDOW = 100;
    public static final int TESTNET_MAJORITY_REJECT_BLOCK_OUTDATED = 75;
    public static final int TESTNET_MAJORITY_ENFORCE_BLOCK_UPGRADE = 51;

    public TestNet2Params() {
        super();
        id = ID_TESTNET;
        packetMagic = 0xfabfb5daL;
        port = 19401;
        addressHeader = 111;
        p2shHeader = 196;
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };
        interval = INTERVAL;
        targetTimespan = TARGET_TIMESPAN;
        digishieldInterval = DIGISHIELD_INTERVAL;
        digishieldTargetTimespan = DIGISHIELD_TARGET_TIMESPAN;
        switchKGWBlock = SWITCH_KGW_BLOCK;
        switchDigishieldBlock = SWITCH_DIGISHIELD_BLOCK;
        switchDGWV3Block = SWITCH_DGW_V3_BLOCK;
        switchAlgoLyra2ReV2 = SWITCH_ALGO_LYRA2_RE_V2;
        maxTarget = Utils.decodeCompactBits(0x1d0fffffL);
        dumpedPrivateKeyHeader = 239;
        genesisBlock.setTime(1388479759L);
        genesisBlock.setDifficultyTarget(0x1e0ffff0L);
        genesisBlock.setNonce(600389L);
        spendableCoinbaseDepth = 100;
        subsidyDecreaseBlockCount = 1051200;
        String genesisHash = genesisBlock.getHashAsString();
        checkState(genesisHash.equals("a0d810b45c92ac12e8c5b312a680caafba52216e5d9649b9dd86f7ad6550a43f"));
        dnsSeeds = null;
        addrSeeds = null;
        bip32HeaderPub = 0x043587CF;
        bip32HeaderPriv = 0x04358394;

        majorityEnforceBlockUpgrade = TESTNET_MAJORITY_ENFORCE_BLOCK_UPGRADE;
        majorityRejectBlockOutdated = TESTNET_MAJORITY_REJECT_BLOCK_OUTDATED;
        majorityWindow = TESTNET_MAJORITY_WINDOW;
    }

    private static TestNet2Params instance;
    public static synchronized TestNet2Params get() {
        if (instance == null) {
            instance = new TestNet2Params();
        }
        return instance;
    }

    @Override
    public String getPaymentProtocolId() {
        return null;
    }
}
