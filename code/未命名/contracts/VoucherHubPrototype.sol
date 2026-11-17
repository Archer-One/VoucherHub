// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/// @notice Interface sketch for the VoucherHub on-chain dispute path.
/// @dev This file intentionally avoids pretending to verify production DRSign
/// proofs until the real bn128 point encoding and pairing verifier are wired in.
contract VoucherHubPrototype {
    struct Channel {
        address user;
        uint64 version;
        bool open;
    }

    mapping(bytes32 => Channel) public channels;
    mapping(bytes32 => bool) public spentNullifier;

    event ChannelOpened(bytes32 indexed cid, address indexed user);
    event ChannelClosed(bytes32 indexed cid, uint64 version);
    event VoucherClaimed(bytes32 indexed cid, bytes32 indexed nullifier);

    function openChannel(bytes32 cid) external {
        require(channels[cid].user == address(0), "channel exists");
        channels[cid] = Channel(msg.sender, 0, true);
        emit ChannelOpened(cid, msg.sender);
    }

    function closeChannel(bytes32 cid, uint64 version) external {
        Channel storage ch = channels[cid];
        require(ch.open, "closed");
        require(msg.sender == ch.user, "not user");
        require(version >= ch.version, "stale");
        ch.version = version;
        ch.open = false;
        emit ChannelClosed(cid, version);
    }

    function markVoucherClaimed(bytes32 cid, bytes32 nullifier) external {
        Channel storage ch = channels[cid];
        require(ch.open, "closed");
        require(!spentNullifier[nullifier], "spent");
        spentNullifier[nullifier] = true;
        emit VoucherClaimed(cid, nullifier);
    }

    function drsignVerifyPlaceholder(bytes calldata) external pure returns (bool) {
        revert("prototype: bn128 DRSign verifier not wired");
    }
}
