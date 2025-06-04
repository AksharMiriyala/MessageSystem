package customEncryption;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;


public class Chat {
    private final int listenPort; //holds port to listen for connections
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    //Holds threads to hold the loop, each connectionHandler, and outgoing connect attempts
    private final List<ConnectionHandler> connections = Collections.synchronizedList(new ArrayList<>());
    //thread safe list of connectionHandler objects, wrapped so that iteration is simpler
    private ServerSocket serverSocket; //the socket on which the peer listens
    
    private static final byte[] SHARED_KEY = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    //16 byte shared key, in real world would be derived securely not hard coded before 
   
    public Chat(int listenPort) {
        this.listenPort = listenPort;
        //stores the integer port, no sockets are created
    }

    public static void main(String[] args) throws Exception { //throws to avoid wrapping calls in try-catch
        if (args.length != 1 && args.length != 3) {
            System.err.println("Usage: java Chat <listenPort> [<remoteHost> <remotePort>]");
            System.exit(1);
        } //only 2 possible invocations, just a listening port, and listening port + connections
        int listenPort = Integer.parseInt(args[0]);
        Chat peer = new Chat(listenPort);
        //Creates an instance of the chat based on the given port
        peer.startServer(); //starts the server
        if (args.length == 3) {//3 arguments  means intent to connect with another peer
            String remoteHost = args[1]; 
            int remotePort = Integer.parseInt(args[2]); //the port to connect to 
            peer.connectToPeer(remoteHost, remotePort); //calls connect method
        }
        peer.readConsoleAndBroadcast(); //sending messages method
    }

    private void startServer() throws IOException {
        serverSocket = new ServerSocket(listenPort); //binds port, throws exception if port is used
        System.out.println("Listening for incoming peers on port " + listenPort + " …");
        //console feedback
        threadPool.submit(() -> { //creates the acceptor thread
            while (!serverSocket.isClosed()) { //loop allows shutdown to throw an exception and break loop
                try {
                    Socket clientSocket = serverSocket.accept(); //clientSocket represents the connection
                    System.out.println("Incoming connection from "
                                       + clientSocket.getRemoteSocketAddress()); //tells user who they are connected to 
                    ConnectionHandler handler = new ConnectionHandler(clientSocket); //wrapping the connection in a class that implements runnable
                    connections.add(handler); //add it to the shared connections list
                    threadPool.submit(handler); //run methods prints incoming messages
                } catch (IOException e) {
                    if (serverSocket.isClosed()) break; //break out if serverSocket is closed by another thread
                    e.printStackTrace(); //otherwise print the stack trace and continue looping
                }
            }
        });
    }

    private void connectToPeer(String host, int port) {
        threadPool.submit(() -> { //uses another thread to avoid blocking the caller of connectToPeer 
            try {
                Socket socket = new Socket(host, port); //attempts to connect
                System.out.println("Connected to peer " + host + ":" + port); //feedback
                ConnectionHandler handler = new ConnectionHandler(socket); //wrap and add connections to pool allowing it to use the same connectionHandler
                connections.add(handler);
                threadPool.submit(handler);
            } catch (IOException e) {
                System.err.println("Failed to connect to " + host + ":" + port + " → " + e.getMessage());
                //exception when other side isn't listening or is unreachable
            }
        });
    }

    private void readConsoleAndBroadcast() {
        try (BufferedReader console = new BufferedReader(new InputStreamReader(System.in))) {
            //decodes bytes the characters, wrapped so readLine() can be called
        	String line;
            while ((line = console.readLine()) != null) { //continues to read lines on users console
                synchronized (connections) { //broadcasts to all active connections
                    for (ConnectionHandler handler : connections) {
                        handler.sendMessage(line);
                    }
                }
            }
        } catch (IOException e) {//print stack trace
            e.printStackTrace();
        } finally {
            shutdown(); //always closes sockets and stops all threads
        }
    }

    private void shutdown() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close(); //if socket is open, closes
            }
            synchronized (connections) { //close all active connections
                for (ConnectionHandler h : connections) {
                    h.close();
                }
            }
            threadPool.shutdownNow(); //shutdown threadPool
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private class ConnectionHandler implements Runnable { //innec class
        private final Socket socket;
        private final BufferedReader in;
        private final BufferedWriter out;
        private final Cipher encryptCipher;
        private final Cipher decryptCipher;

        ConnectionHandler(Socket socket) throws IOException {
            this.socket = socket; //stores socket reference
            try { 
            	SecretKeySpec keySpec = new SecretKeySpec(SHARED_KEY, "AES");
            	encryptCipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            	decryptCipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            	encryptCipher.init(Cipher.ENCRYPT_MODE, keySpec);
            	decryptCipher.init(Cipher.DECRYPT_MODE, keySpec);
            }catch(GeneralSecurityException e) {
            	throw new IOException("Failed to encrypt", e);
            }
                      
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            //makes sure input and output streams interpret bytes as UTF-8 text
        }

        @Override
        public void run() { //run method, implementing runnable
            String peerAddy = socket.getRemoteSocketAddress().toString();
            //representation of endpoint, used to identify where message comes from
            try {
                String encryptedLine;
                while ((encryptedLine = in.readLine()) != null) { //starts when peer sends a line
                	
                	System.out.println("raw recieved text -> " + encryptedLine); //debug line proves the encrypted line was received
                	
                	String decrypted = decrypt(encryptedLine);
                    System.out.println("[" + peerAddy + "] " + decrypted);//prints message in console
                }
            } catch (IOException e) { //shows connection lost when disconnected
                System.err.println("Connection to " + peerAddy + " lost: " + e.getMessage());
            } finally {
                close(); //afterwards always close everything
            }
        }

        synchronized void sendMessage(String msg) {
        	//synchronized because multiple threads can attempt to send message concurrently
            try {
            	String cipherText = encrypt(msg);
            	
            	System.out.println("sent encypted text -> " + cipherText); //debug line to prove an encryption to place
            	
                out.write(cipherText); //writes the string
                out.write("\r\n");//end of line
                out.flush(); //removes excess bytes
            } catch (IOException e) {
                System.err.println("Failed to send to " + socket.getRemoteSocketAddress() + ": " + e.getMessage());
                close();
            }
        }
        
        private String encrypt(String text) throws IOException{
        	try {
        		byte[] regBytes = text.getBytes(StandardCharsets.UTF_8);
        		byte[] cipherBytes = encryptCipher.doFinal(regBytes);
        		return Base64.getEncoder().encodeToString(cipherBytes);
        	}catch (GeneralSecurityException e) {
        		throw new IOException("Encryption error", e);
        	}
        }
        
        private String decrypt(String text) throws IOException{
        	try {
        		byte[] cipherBytes = Base64.getDecoder().decode(text);
        		byte[] regBytes = decryptCipher.doFinal(cipherBytes);
        		return new String(regBytes, StandardCharsets.UTF_8);
        	}catch(GeneralSecurityException | IllegalArgumentException e) {
        		return "decrypt error";
        	}
        }

        synchronized void close() {
        	//synchronized to avoid data race
            try { in.close(); } catch (IOException ignored) {} //closes everything
            try { out.close(); } catch (IOException ignored) {}
            try { socket.close(); } catch (IOException ignored) {}
            connections.remove(this); //removes handler from shared list 
        }
    }
}