package com.github.catvod.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 兼容老设备/电视盒子的 SSL Socket 工厂：
 * 强制打开 TLSv1 / TLSv1.1 / TLSv1.2 / TLSv1.3，避免部分设备默认只启用个别版本，
 * 导致与配置服务器握手时报 "Failure in SSL library, usually a protocol error"。
 */
public final class TlsCompatSocketFactory extends SSLSocketFactory {

    private static final String[] PROTOCOLS = {"TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"};

    private final SSLSocketFactory delegate;

    public TlsCompatSocketFactory(SSLSocketFactory delegate) {
        this.delegate = delegate;
    }

    private Socket patch(Socket socket) {
        if (socket instanceof SSLSocket) {
            ((SSLSocket) socket).setEnabledProtocols(PROTOCOLS);
        }
        return socket;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() throws IOException {
        return patch(delegate.createSocket());
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return patch(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return patch(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return patch(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return patch(delegate.createSocket(address, port, localAddress, localPort));
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return patch(delegate.createSocket(s, host, port, autoClose));
    }
}
