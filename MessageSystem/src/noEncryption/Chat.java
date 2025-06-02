package noEncryption;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Chat {
    private final int listenPort;
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private final List<ConnectionHandler> connections = Collections.synchronizedList(new ArrayList<>());
    private ServerSocket serverSocket;

    public Chat(int listenPort) {
        this.listenPort = listenPort;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 && args.length != 3) {
            System.err.println("Usage: java Chat <listenPort> [<remoteHost> <remotePort>]");
            System.exit(1);
        }
        int listenPort = Integer.parseInt(args[0]);
        Chat peer = new Chat(listenPort);
        peer.startServer();
        if (args.length == 3) {
            String remoteHost = args[1];
            int remotePort = Integer.parseInt(args[2]);
            peer.connectToPeer(remoteHost, remotePort);
        }
        peer.readConsoleAndBroadcast();
    }

    private void startServer() throws IOException {
        serverSocket = new ServerSocket(listenPort);
        System.out.println("Listening for incoming peers on port " + listenPort + " …");
        threadPool.submit(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("Incoming connection from "
                                       + clientSocket.getRemoteSocketAddress());
                    ConnectionHandler handler = new ConnectionHandler(clientSocket);
                    connections.add(handler);
                    threadPool.submit(handler);
                } catch (IOException e) {
                    if (serverSocket.isClosed()) break;
                    e.printStackTrace();
                }
            }
        });
    }

    private void connectToPeer(String host, int port) {
        threadPool.submit(() -> {
            try {
                Socket socket = new Socket(host, port);
                System.out.println("Connected to peer " + host + ":" + port);
                ConnectionHandler handler = new ConnectionHandler(socket);
                connections.add(handler);
                threadPool.submit(handler);
            } catch (IOException e) {
                System.err.println("Failed to connect to " + host + ":" + port + " → " + e.getMessage());
            }
        });
    }

    private void readConsoleAndBroadcast() {
        try (BufferedReader console = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = console.readLine()) != null) {
                synchronized (connections) {
                    for (ConnectionHandler handler : connections) {
                        handler.sendMessage("[Me] " + line);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            shutdown();
        }
    }

    private void shutdown() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            synchronized (connections) {
                for (ConnectionHandler h : connections) {
                    h.close();
                }
            }
            threadPool.shutdownNow();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private class ConnectionHandler implements Runnable {
        private final Socket socket;
        private final BufferedReader in;
        private final BufferedWriter out;

        ConnectionHandler(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
        }

        @Override
        public void run() {
            String peerAddr = socket.getRemoteSocketAddress().toString();
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    System.out.println("[" + peerAddr + "] " + line);
                }
            } catch (IOException e) {
                System.err.println("Connection to " + peerAddr + " lost: " + e.getMessage());
            } finally {
                close();
            }
        }

        synchronized void sendMessage(String msg) {
            try {
                out.write(msg);
                out.write("\r\n");
                out.flush();
            } catch (IOException e) {
                System.err.println("Failed to send to " + socket.getRemoteSocketAddress() + ": " + e.getMessage());
                close();
            }
        }

        synchronized void close() {
            try { in.close(); } catch (IOException ignored) {}
            try { out.close(); } catch (IOException ignored) {}
            try { socket.close(); } catch (IOException ignored) {}
            connections.remove(this);
        }
    }
}
