// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

library BenchmarkECDSA {
    function recover(bytes32 digest, bytes calldata signature) internal pure returns (address signer) {
        require(signature.length == 65, "signature length");
        bytes32 r;
        bytes32 s;
        uint8 v;
        assembly {
            r := calldataload(signature.offset)
            s := calldataload(add(signature.offset, 32))
            v := byte(0, calldataload(add(signature.offset, 64)))
        }
        if (v < 27) v += 27;
        require(v == 27 || v == 28, "signature v");
        signer = ecrecover(digest, v, r, s);
        require(signer != address(0), "signature recovery");
    }
}

contract VoucherHubLifecycleGas {
    struct Channel {
        address user;
        address hub;
        bytes32 balanceCommitment;
        uint64 version;
        bool open;
    }

    mapping(bytes32 => Channel) public channels;

    function commitment(uint256 balance, uint256 blinding) public pure returns (bytes32) {
        return keccak256(abi.encode(balance, blinding));
    }

    function openDigest(bytes32 cid, address user, address hub, bytes32 stateCommitment)
        public view returns (bytes32)
    {
        return keccak256(abi.encode(address(this), block.chainid, "VH_OPEN", cid, user, hub, stateCommitment));
    }

    function closeDigest(bytes32 cid, uint64 version, bytes32 stateCommitment)
        public view returns (bytes32)
    {
        return keccak256(abi.encode(address(this), block.chainid, "VH_CLOSE", cid, version, stateCommitment));
    }

    function openBlindChannel(
        bytes32 cid, address user, address hub, uint256 balance, uint256 blinding,
        bytes calldata userSignature, bytes calldata hubSignature
    ) external {
        require(!channels[cid].open, "channel exists");
        bytes32 stateCommitment = commitment(balance, blinding);
        bytes32 digest = openDigest(cid, user, hub, stateCommitment);
        require(BenchmarkECDSA.recover(digest, userSignature) == user, "user auth");
        require(BenchmarkECDSA.recover(digest, hubSignature) == hub, "hub auth");
        channels[cid] = Channel(user, hub, stateCommitment, 0, true);
    }

    function closeIdeal(
        bytes32 cid, uint64 version, uint256 finalBalance, uint256 finalBlinding,
        bytes calldata userSignature, bytes calldata hubSignature
    ) external {
        Channel storage channel = channels[cid];
        require(channel.open && version > channel.version, "invalid state");
        bytes32 finalCommitment = commitment(finalBalance, finalBlinding);
        bytes32 digest = closeDigest(cid, version, finalCommitment);
        require(BenchmarkECDSA.recover(digest, userSignature) == channel.user, "user auth");
        require(BenchmarkECDSA.recover(digest, hubSignature) == channel.hub, "hub auth");
        channel.balanceCommitment = finalCommitment;
        channel.version = version;
        channel.open = false;
    }
}

contract VoucherHubBatchRedeemGas is VoucherHubLifecycleGas {
    struct Voucher {
        bytes32 nullifier;
        uint256 amount;
        uint256 blinding;
        address recipient;
        bytes hubSignature;
    }

    mapping(bytes32 => bool) public redeemed;

    function batchCommitment(uint256 totalAmount, uint256 totalBlinding)
        public pure returns (bytes32)
    {
        return keccak256(abi.encode(totalAmount, totalBlinding));
    }

    function voucherDigest(
        bytes32 cid, bytes32 nullifier, uint256 amount, uint256 blinding, address recipient
    ) public view returns (bytes32) {
        return keccak256(abi.encode(
            address(this), block.chainid, "VH_VOUCHER", cid, nullifier, amount, blinding, recipient));
    }

    function batchCloseDigest(bytes32 cid, uint64 version, bytes32 aggregateCommitment, uint256 count)
        public view returns (bytes32)
    {
        return keccak256(abi.encode(
            address(this), block.chainid, "VH_BATCH_CLOSE", cid, version, aggregateCommitment, count));
    }

    function _redeemVoucher(bytes32 cid, address user, address hub, Voucher calldata voucher)
        private returns (uint256 amount, uint256 blinding)
    {
        require(voucher.recipient == user, "recipient");
        require(!redeemed[voucher.nullifier], "already redeemed");
        bytes32 voucherHash = voucherDigest(
            cid, voucher.nullifier, voucher.amount, voucher.blinding, voucher.recipient);
        require(BenchmarkECDSA.recover(voucherHash, voucher.hubSignature) == hub, "voucher auth");
        redeemed[voucher.nullifier] = true;
        return (voucher.amount, voucher.blinding);
    }

    function closeWithBatchRedeem(
        bytes32 cid, uint64 version, bytes32 aggregateCommitment, Voucher[] calldata vouchers,
        bytes calldata userSignature, bytes calldata hubSignature
    ) external {
        Channel storage channel = channels[cid];
        require(channel.open && version > channel.version, "invalid state");
        bytes32 digest = batchCloseDigest(cid, version, aggregateCommitment, vouchers.length);
        require(BenchmarkECDSA.recover(digest, userSignature) == channel.user, "user auth");
        require(BenchmarkECDSA.recover(digest, hubSignature) == channel.hub, "hub auth");

        uint256 totalAmount;
        uint256 totalBlinding;
        for (uint256 i = 0; i < vouchers.length; i++) {
            (uint256 amount, uint256 blinding) = _redeemVoucher(
                cid, channel.user, channel.hub, vouchers[i]);
            totalAmount += amount;
            totalBlinding += blinding;
        }
        require(batchCommitment(totalAmount, totalBlinding) == aggregateCommitment, "aggregate opening");
        channel.balanceCommitment = aggregateCommitment;
        channel.version = version;
        channel.open = false;
    }
}

contract AccioNormalChannelGas is VoucherHubLifecycleGas {
    // The normal comparison path intentionally reuses the authenticated
    // lifecycle but stores an explicit balance rather than a hidden state.
    mapping(bytes32 => uint256) public explicitBalance;

    function recordOpenBalance(bytes32 cid, uint256 balance) external {
        require(channels[cid].open, "channel not open");
        explicitBalance[cid] = balance;
    }

    function recordClosedBalance(bytes32 cid, uint256 balance) external {
        require(!channels[cid].open && channels[cid].version > 0, "channel not closed");
        explicitBalance[cid] = balance;
    }
}

contract AccioHiddenLifecycleGas is VoucherHubLifecycleGas {
    function pairingIdentity(uint256 rounds) public view returns (bool) {
        // Two point-at-infinity pairs are a valid pairing identity.  Keeping
        // the full 12-word input charges the two-pair precompile workload
        // deterministically without embedding protocol-specific proof data.
        uint256[12] memory input;
        uint256[1] memory output;
        for (uint256 i = 0; i < rounds; i++) {
            bool success;
            assembly {
                success := staticcall(gas(), 8, input, 0x180, output, 0x20)
            }
            require(success && output[0] == 1, "pairing check");
        }
        return true;
    }

    function openHidden(
        bytes32 cid, address user, address hub, uint256 balance, uint256 blinding,
        bytes calldata userSignature, bytes calldata hubSignature, uint256 pairingRounds
    ) external {
        require(pairingIdentity(pairingRounds), "hidden proof");
        require(!channels[cid].open, "channel exists");
        bytes32 stateCommitment = commitment(balance, blinding);
        bytes32 digest = openDigest(cid, user, hub, stateCommitment);
        require(BenchmarkECDSA.recover(digest, userSignature) == user, "user auth");
        require(BenchmarkECDSA.recover(digest, hubSignature) == hub, "hub auth");
        channels[cid] = Channel(user, hub, stateCommitment, 0, true);
    }

    function closeHidden(
        bytes32 cid, uint64 version, uint256 finalBalance, uint256 finalBlinding,
        bytes calldata userSignature, bytes calldata hubSignature, uint256 pairingRounds
    ) external {
        require(pairingIdentity(pairingRounds), "hidden proof");
        Channel storage channel = channels[cid];
        require(channel.open && version > channel.version, "invalid state");
        bytes32 finalCommitment = commitment(finalBalance, finalBlinding);
        bytes32 digest = closeDigest(cid, version, finalCommitment);
        require(BenchmarkECDSA.recover(digest, userSignature) == channel.user, "user auth");
        require(BenchmarkECDSA.recover(digest, hubSignature) == channel.hub, "hub auth");
        channel.balanceCommitment = finalCommitment;
        channel.version = version;
        channel.open = false;
    }
}
