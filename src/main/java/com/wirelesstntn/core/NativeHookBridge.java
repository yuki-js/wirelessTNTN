package com.wirelesstntn.core;

import java.util.Objects;

public final class NativeHookBridge {

    private final SecureElementTransport transport;

    public NativeHookBridge(SecureElementTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    public byte[] passThrough(byte[] nfcCommandApdu, SecureElementTarget target) {
        Objects.requireNonNull(nfcCommandApdu, "nfcCommandApdu must not be null");
        Objects.requireNonNull(target, "target must not be null");
        return transport.transceive(nfcCommandApdu, target);
    }

    public interface SecureElementTransport {
        byte[] transceive(byte[] commandApdu, SecureElementTarget target);
    }
}
