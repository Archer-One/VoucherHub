package org.voucherhub.common;

import org.voucherhub.common.crypto.G2Point;
import org.voucherhub.common.protocol.Model.CompletedPayment;
import org.voucherhub.common.protocol.Model.ChannelState;
import org.voucherhub.common.protocol.Model.WalletVoucher;
import org.voucherhub.common.crypto.SigmaProtocol.OpeningProof;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EvaluationWire {
    private EvaluationWire() {
    }

    public static final class Request implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String command;
        public final String operationId;
        public final List<String> recipients;
        public final List<Long> amounts;
        public final List<WalletVoucher> inputs;
        public final List<CompletedPayment> payments;
        public final G2Point hubPublicKey;
        public final OpeningProof openingProof;
        public final ChannelState channelState;

        public Request(String command, String operationId, List<String> recipients, List<Long> amounts,
                       List<WalletVoucher> inputs, List<CompletedPayment> payments, G2Point hubPublicKey) {
            this.command = command;
            this.operationId = operationId;
            this.recipients = recipients == null ? Collections.emptyList() : new ArrayList<>(recipients);
            this.amounts = amounts == null ? Collections.emptyList() : new ArrayList<>(amounts);
            this.inputs = inputs == null ? Collections.emptyList() : new ArrayList<>(inputs);
            this.payments = payments == null ? Collections.emptyList() : new ArrayList<>(payments);
            this.hubPublicKey = hubPublicKey;
            this.openingProof = null;
            this.channelState = null;
        }

        public Request(String command, String operationId, List<String> recipients, List<Long> amounts,
                       List<WalletVoucher> inputs, List<CompletedPayment> payments, G2Point hubPublicKey,
                       OpeningProof openingProof) {
            this(command, operationId, recipients, amounts, inputs, payments, hubPublicKey,
                    openingProof, null);
        }

        public Request(String command, String operationId, List<String> recipients, List<Long> amounts,
                       List<WalletVoucher> inputs, List<CompletedPayment> payments, G2Point hubPublicKey,
                       OpeningProof openingProof, ChannelState channelState) {
            this.command = command;
            this.operationId = operationId;
            this.recipients = recipients == null ? Collections.emptyList() : new ArrayList<>(recipients);
            this.amounts = amounts == null ? Collections.emptyList() : new ArrayList<>(amounts);
            this.inputs = inputs == null ? Collections.emptyList() : new ArrayList<>(inputs);
            this.payments = payments == null ? Collections.emptyList() : new ArrayList<>(payments);
            this.hubPublicKey = hubPublicKey;
            this.openingProof = openingProof;
            this.channelState = channelState;
        }
    }

    public static final class Response implements Serializable {
        private static final long serialVersionUID = 1L;
        public final boolean success;
        public final String reason;
        public final List<CompletedPayment> payments;
        public final G2Point hubPublicKey;
        public final long serviceNanos;
        public final List<String> recipients;
        public final List<Long> amounts;
        public final ChannelState channelState;

        public Response(boolean success, String reason, List<CompletedPayment> payments,
                        G2Point hubPublicKey, long serviceNanos) {
            this.success = success;
            this.reason = reason;
            this.payments = payments == null ? Collections.emptyList() : new ArrayList<>(payments);
            this.hubPublicKey = hubPublicKey;
            this.serviceNanos = serviceNanos;
            this.recipients = Collections.emptyList();
            this.amounts = Collections.emptyList();
            this.channelState = null;
        }

        public Response(boolean success, String reason, List<CompletedPayment> payments,
                        G2Point hubPublicKey, long serviceNanos, List<String> recipients,
                        List<Long> amounts, ChannelState channelState) {
            this.success = success;
            this.reason = reason;
            this.payments = payments == null ? Collections.emptyList() : new ArrayList<>(payments);
            this.hubPublicKey = hubPublicKey;
            this.serviceNanos = serviceNanos;
            this.recipients = recipients == null ? Collections.emptyList() : new ArrayList<>(recipients);
            this.amounts = amounts == null ? Collections.emptyList() : new ArrayList<>(amounts);
            this.channelState = channelState;
        }
    }
}
