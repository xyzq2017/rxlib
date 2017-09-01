package org.rx.socket;

import org.rx.common.Tuple;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.rx.common.Contract.require;
import static org.rx.util.App.logError;
import static org.rx.util.App.retry;
import static org.rx.util.AsyncTask.TaskFactory;
import static org.rx.socket.SocketPool.Pool;
import static org.rx.socket.SocketPool.PooledSocket;

/**
 * Created by IntelliJ IDEA. User: wangxiaoming Date: 2017/8/25
 */
public class DirectSocket extends Traceable implements AutoCloseable {
    private static class ClientItem implements AutoCloseable {
        private final DirectSocket owner;
        public final Socket        sock;
        public final IOStream      ioStream;
        public final PooledSocket  directSock;
        public final IOStream      directIoStream;

        public boolean isClosed() {
            return !(sock.isConnected() && !sock.isClosed() && directSock.isConnected());
        }

        public ClientItem(Socket client, DirectSocket owner) {
            sock = client;
            try {
                directSock = retry(p -> Pool.borrowSocket(p.directAddress), owner, owner.connectRetryCount);
                ioStream = new IOStream(sock.getInputStream(), directSock.socket.getOutputStream());
                directIoStream = new IOStream(directSock.socket.getInputStream(), sock.getOutputStream());
            } catch (IOException ex) {
                throw new SocketException((InetSocketAddress) sock.getLocalSocketAddress(), ex);
            }
            this.owner = owner;
        }

        @Override
        public void close() {
            owner.getTracer().writeLine("%s client[%s, %s] close..", owner.getTimeString(),
                    sock.getLocalSocketAddress(), directSock.socket.getLocalSocketAddress());
            try {
                sock.close();
                directSock.close();
            } catch (IOException ex) {
                logError(ex, "DirectSocket.ClientItem.close()");
            }
        }
    }

    public static final InetAddress LocalAddress, AnyAddress;
    private static final int        DefaultBacklog           = 128;
    private static final int        DefaultConnectRetryCount = 4;

    static {
        LocalAddress = InetAddress.getLoopbackAddress();
        try {
            AnyAddress = InetAddress.getByName("0.0.0.0");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private volatile boolean       isClosed;
    private InetSocketAddress      directAddress;
    private final ServerSocket     server;
    private final List<ClientItem> clients;
    private volatile int           connectRetryCount;

    public boolean isClosed() {
        return !(!isClosed && !server.isClosed());
    }

    public InetSocketAddress getDirectAddress() {
        return directAddress;
    }

    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) server.getLocalSocketAddress();
    }

    public Stream<Tuple<Socket, Socket>> getClients() {
        return getClientsCopy().stream().map(p -> Tuple.of(p.sock, p.directSock.socket));
    }

    public int getConnectRetryCount() {
        return connectRetryCount;
    }

    public void setConnectRetryCount(int connectRetryCount) {
        if (connectRetryCount <= 0) {
            connectRetryCount = 1;
        }
        this.connectRetryCount = connectRetryCount;
    }

    public DirectSocket(int listenPort, InetSocketAddress directAddr) {
        this(new InetSocketAddress(AnyAddress, listenPort), directAddr);
    }

    public DirectSocket(InetSocketAddress listenAddr, InetSocketAddress directAddr) {
        require(listenAddr, directAddr);

        try {
            server = new ServerSocket();
            server.bind(listenAddr, DefaultBacklog);
        } catch (IOException ex) {
            throw new SocketException(listenAddr, ex);
        }
        directAddress = directAddr;
        clients = Collections.synchronizedList(new ArrayList<>());
        connectRetryCount = DefaultConnectRetryCount;
        String taskName = String.format("DirectSocket[%s to %s]", listenAddr, directAddr);
        Tracer tracer = new Tracer();
        tracer.setPrefix(taskName + " ");
        setTracer(tracer);
        TaskFactory.run(() -> {
            getTracer().writeLine("%s start..", getTimeString());
            while (!isClosed()) {
                try {
                    ClientItem client = new ClientItem(server.accept(), this);
                    clients.add(client);
                    onReceive(client, taskName);
                } catch (IOException ex) {
                    logError(ex, taskName);
                }
            }
            close();
        }, taskName);
    }

    @Override
    public synchronized void close() {
        if (isClosed) {
            return;
        }
        isClosed = true;
        try {
            for (ClientItem client : getClientsCopy()) {
                try {
                    client.close();
                } catch (Exception ex) {
                    logError(ex, "DirectSocket.ClientItem.close()");
                }
            }
            clients.clear();
            server.close();
        } catch (IOException ex) {
            logError(ex, "DirectSocket.close()");
        }
        getTracer().writeLine("%s close..", getTimeString());
    }

    private List<ClientItem> getClientsCopy() {
        return new ArrayList<>(clients);
    }

    private void onReceive(ClientItem client, String taskName) {
        TaskFactory.run(() -> {
            int recv = client.ioStream.directData(p -> !client.isClosed(), p -> {
                getTracer().writeLine("%s sent %s bytes from %s to %s..", getTimeString(), p,
                        client.sock.getLocalSocketAddress(), client.directSock.socket.getRemoteSocketAddress());
                return true;
            });
            if (recv == 0) {
                client.close();
            }
        }, String.format("%s[ioStream]", taskName));
        TaskFactory.run(() -> {
            int recv = client.directIoStream.directData(p -> !client.isClosed(), p -> {
                getTracer().writeLine("%s recv %s bytes from %s to %s..", getTimeString(), p,
                        client.directSock.socket.getRemoteSocketAddress(), client.sock.getLocalSocketAddress());
                return true;
            });
            if (recv == 0) {
                client.close();
            }
        }, String.format("%s[directIoStream]", taskName));
    }
}