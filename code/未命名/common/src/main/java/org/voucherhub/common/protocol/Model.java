package org.voucherhub.common.protocol;

import org.voucherhub.common.crypto.DRSign;
import org.voucherhub.common.crypto.G1Point;
import org.voucherhub.common.crypto.G2Point;
import org.voucherhub.common.crypto.Scalar;

import java.util.ArrayList;
import java.util.List;

public final class Model {
    private Model() {
    }

    public static final class Channel {
        public final String cid;
        public long userBalance;
        public long hubBalance;
        public long version;
        public G1Point cUser;
        public G1Point cHub;

        public Channel(String cid, long userBalance, long hubBalance, G1Point cUser, G1Point cHub) {
            this.cid = cid;
            this.userBalance = userBalance;
            this.hubBalance = hubBalance;
            this.cUser = cUser;
            this.cHub = cHub;
        }
    }

    public static final class ChannelState implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String cid;
        public final long version;
        public final long userBalance;
        public final long hubBalance;
        public final int creditedVouchers;
        public final long creditedAmount;
        public final G1Point cUser;
        public final G1Point cHub;
        public final G1Point statement;
        public final DRSign.Signature signature;

        public ChannelState(String cid, long version, long userBalance, long hubBalance,
                            int creditedVouchers, long creditedAmount, G1Point cUser, G1Point cHub,
                            G1Point statement, DRSign.Signature signature) {
            this.cid = cid;
            this.version = version;
            this.userBalance = userBalance;
            this.hubBalance = hubBalance;
            this.creditedVouchers = creditedVouchers;
            this.creditedAmount = creditedAmount;
            this.cUser = cUser;
            this.cHub = cHub;
            this.statement = statement;
            this.signature = signature;
        }
    }

    public static final class Invoice implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final long amount;
        public final Scalar rX;
        public final Scalar rId;
        public final Scalar rBlind;
        public final String cidB;

        public Invoice(long amount, Scalar rX, Scalar rId, Scalar rBlind, String cidB) {
            this.amount = amount;
            this.rX = rX;
            this.rId = rId;
            this.rBlind = rBlind;
            this.cidB = cidB;
        }
    }

    public static final class MintRequest {
        public final String cidA;
        public final long version;
        public final long amount;
        public final G1Point cAmtA;
        public final G1Point cX;
        public final G1Point newAlice;
        public final G1Point newHub;
        public final boolean structureProofValid;

        public MintRequest(String cidA, long version, long amount, G1Point cAmtA, G1Point cX,
                           G1Point newAlice, G1Point newHub, boolean structureProofValid) {
            this.cidA = cidA;
            this.version = version;
            this.amount = amount;
            this.cAmtA = cAmtA;
            this.cX = cX;
            this.newAlice = newAlice;
            this.newHub = newHub;
            this.structureProofValid = structureProofValid;
        }
    }

    public static final class PreCredential {
        public final Scalar t;
        public final G1Point T;
        public final DRSign.BlindSignature blindSignature;

        public PreCredential(Scalar t, G1Point tPoint, DRSign.BlindSignature blindSignature) {
            this.t = t;
            this.T = tPoint;
            this.blindSignature = blindSignature;
        }
    }

    public static final class ReleasedCredential {
        public final G1Point cX;
        public final DRSign.BlindSignature blindSignature;
        public final String domain;

        public ReleasedCredential(G1Point cX, DRSign.BlindSignature blindSignature, String domain) {
            this.cX = cX;
            this.blindSignature = blindSignature;
            this.domain = domain;
        }
    }

    public static final class WalletVoucher implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final Invoice invoice;
        public final G1Point cXPrime;
        public final DRSign.Signature signature;
        public final String nullifier;

        public WalletVoucher(Invoice invoice, G1Point cXPrime, DRSign.Signature signature, String nullifier) {
            this.invoice = invoice;
            this.cXPrime = cXPrime;
            this.signature = signature;
            this.nullifier = nullifier;
        }
    }

    public static final class RedeemResult {
        public final boolean success;
        public final String reason;

        public RedeemResult(boolean success, String reason) {
            this.success = success;
            this.reason = reason;
        }
    }

    public static final class BatchResult {
        public final boolean success;
        public final int accepted;
        public final long totalAmount;

        public BatchResult(boolean success, int accepted, long totalAmount) {
            this.success = success;
            this.accepted = accepted;
            this.totalAmount = totalAmount;
        }
    }

    public static final class FreshnessProof implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String operationId;
        public final String predecessorNullifier;
        public final String voucherNullifier;
        public final G1Point statement;
        public final DRSign.Signature signature;

        public FreshnessProof(String operationId, String predecessorNullifier, String voucherNullifier,
                              G1Point statement, DRSign.Signature signature) {
            this.operationId = operationId;
            this.predecessorNullifier = predecessorNullifier;
            this.voucherNullifier = voucherNullifier;
            this.statement = statement;
            this.signature = signature;
        }
    }

    public static final class CompletedPayment implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final WalletVoucher voucher;
        public final FreshnessProof freshness;

        public CompletedPayment(WalletVoucher voucher, FreshnessProof freshness) {
            this.voucher = voucher;
            this.freshness = freshness;
        }
    }

    public static final class TransferResult implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final boolean success;
        public final String reason;
        public final List<CompletedPayment> outputs;

        public TransferResult(boolean success, String reason, List<CompletedPayment> outputs) {
            this.success = success;
            this.reason = reason;
            this.outputs = new ArrayList<>(outputs);
        }
    }

    public static final class ProtocolMetrics {
        public final long hubSignatureNanos;
        public final long proofGenerationNanos;
        public final long proofVerificationNanos;

        public ProtocolMetrics(long hubSignatureNanos, long proofGenerationNanos,
                               long proofVerificationNanos) {
            this.hubSignatureNanos = hubSignatureNanos;
            this.proofGenerationNanos = proofGenerationNanos;
            this.proofVerificationNanos = proofVerificationNanos;
        }
    }

    public static final class PartyConfig {
        public String hubHost = "127.0.0.1";
        public int hubPort = 9000;
        public String payeeHost = "127.0.0.1";
        public int payeePort = 9001;
        public String rpcUrl = "http://127.0.0.1:8545";
        public String contractAddress = "prototype";
        public int runs = 30;
        public final List<Integer> batchSizes = new ArrayList<>();
    }
}
