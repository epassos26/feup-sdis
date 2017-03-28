package peers;

import java.io.*;
import java.net.InetAddress;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class PeerService {

    public static final String CRLF = "\r\n";
    public static final int CHUNK_SIZE = 64000;
    public static final int ERROR = -1;

    private String serverId;
    private String protocolVersion;
    private String serviceAccessPoint;

    private PeerChannel controlChannel;
    private PeerChannel dataBackupChannel;
    private PeerChannel dataRestoreChannel;

    private String myFilesPath;
    private String chunksPath;
    private String restoredFilesPath;

    private PeerClientLink initiatorPeer;

    /**
     * registers the peers that have stored chunks
     * key = <fileID>_<ChunkNo>
     * value = array with the peer id of the peers that have stored that chunk
     *
     */
    private ConcurrentHashMap<String,ArrayList<Integer>> chunkMap;

    /**
     * stores the desired replication degree for every file the
     * peer has stored or has chunks of
     */
    private ConcurrentHashMap<String,Integer> fileReplicationDegrees;

    /**
     * stores the number of chunks every file has
     */
    private ConcurrentHashMap<String,Integer> fileChunkNum;

    /**
     *  registers the chunk number of the stored chunks
     *  key = <fileID>
     *  value = array with the chunk numbers of the stored chunks
     */
    private ConcurrentHashMap<String,ArrayList<Integer>> storedChunks;

    /**
     * registers the number of chunks written to a file
     * key = <fileID>_<ChunkNo>
     * value = true if the file is restored false otherwise
     */
    private ConcurrentHashMap<String,Boolean> allRestoredChunks;

    /**
     * Stores chunks when they aren't written to the file
     * key = <fileID>
     * value = byte array
     */
    private ConcurrentHashMap<String,byte[]> restoredChunks;

    public PeerService(String serverId,String protocolVersion, String serviceAccessPoint,InetAddress mcAddr,int mcPort,InetAddress mdbAddr,int mdbPort,
                       InetAddress mdrAddr,int mdrPort) throws IOException {

        this.serverId = serverId;
        this.protocolVersion = protocolVersion;
        this.serviceAccessPoint = serviceAccessPoint;

        controlChannel = new PeerChannel(mcAddr,mcPort,this);
        System.out.println("Control Channel ready! Listening...");
        dataBackupChannel = new PeerChannel(mdbAddr, mdbPort,this);
        System.out.println("Data Backup Channel ready! Listening...");
        dataRestoreChannel = new PeerChannel(mdrAddr,mdrPort,this);
        System.out.println("Restore Channel ready! Listening...");

        System.out.println("Multicast channel addr: "+ mcAddr+" port: "+ mcPort);
        System.out.println("Multicast data backup addr: "+ mdbAddr+" port: "+ mdbPort);
        System.out.println("Multicast data restore addr: "+ mdrAddr+" port: "+ mdrPort);

        System.out.println("Server ID: " + serverId);

        initiatorPeer = new PeerClientLink(this);

        try{
            //TODO add ip address
            Registry registry = LocateRegistry.getRegistry();
            registry.bind(this.serviceAccessPoint,initiatorPeer);
        }catch (Exception e){
            //TODO add rebind
            System.out.println("Peer error: "+ e.getMessage());
            e.printStackTrace();
        }


        chunksPath = serverId + "/chunks";
        myFilesPath = serverId + "/my_files";
        restoredFilesPath = serverId + "/restored_files";

        createDir(serverId);
        createDir(myFilesPath);
        createDir(chunksPath);
        createDir(restoredFilesPath);

        controlChannel.receiveMessage();
        dataBackupChannel.receiveMessage();
        dataRestoreChannel.receiveMessage();

        chunkMap = new ConcurrentHashMap<>();
        fileReplicationDegrees = new ConcurrentHashMap<>();
        fileChunkNum = new ConcurrentHashMap<>();
        storedChunks = new ConcurrentHashMap<>();
        allRestoredChunks = new ConcurrentHashMap<>();
        restoredChunks = new ConcurrentHashMap<>();
    }

    public void createDir(String folderPath) {

        File file = new File(folderPath);

        if(file.mkdir()){
            System.out.println("Directory: " + folderPath + " created");
        }

    }

    private String makeHeader(String... fields) {

        String header = "";

        for(String field : fields){
            header = header.concat(field+" ");
        }

        header = header.concat(CRLF + CRLF);

        return header;
    }

    /**
     * Places a file in the replication degree hash map and
     * initializes a list in the peer list
     * @param fileID file ID for the sent file
     * @param replicationDegree desired replication degree for the file
     */
    public void registerFile(String fileID, int replicationDegree){
        if(fileReplicationDegrees.get(fileID) != null){
            fileReplicationDegrees.remove(fileID);
        }

        fileReplicationDegrees.put(fileID,replicationDegree);
    }

    /**
     * Registers a chunk in the storedChunks HashMap
     * checks if the file is already registered, if not, it is registered
     * checks if the chunk is already stored, if not, it is stored
     *
     * @param fileID
     * @param chunkNo
     * @param replicationDegree
     * @return
     */
    private boolean registerChunk(String fileID, int chunkNo, int replicationDegree){
        ArrayList<Integer> fileChunks = storedChunks.get(fileID);
        if (fileChunks == null){ // no chunks registered for this file
            registerFile(fileID, replicationDegree);
            fileChunks = new ArrayList<>();
            fileChunks.add(chunkNo);
            storedChunks.put(fileID,fileChunks);
            return true;
        }
        for (Integer fileChunkNo : fileChunks) {
            if (fileChunkNo == chunkNo)
                return false;
        }
        fileChunks.add(chunkNo);
        return true;
    }

    public boolean registerNumChunks(String fileID, int numChunks){
        if(fileChunkNum.get(fileID) != null)
            return false;

        fileChunkNum.put(fileID,numChunks);
        return true;
    }

    public int getNumChunks(String fileID){

        Object ChunksNo = fileChunkNum.get(fileID);

        if(ChunksNo == null)
            return ERROR;

        return (int)ChunksNo;
    }

    private int getReplicationDegree(String fileID, String chunkNo){
        ArrayList chunkPeers = chunkMap.get(fileID+'_'+chunkNo);
        if(chunkPeers == null)
            return -1;
        else return chunkPeers.size();
    }

    public void requestChunkBackup(String fileId, int chunkNo, int replicationDegree, byte[] chunk) throws IOException {

        Runnable task = () -> {
            int counter = 1;

            do {
                String header = makeHeader("PUTCHUNK", protocolVersion, serverId, fileId,
                        Integer.toString(chunkNo), Integer.toString(replicationDegree));

                byte[] headerBytes = header.getBytes();
                byte[] buf = new byte[headerBytes.length + chunk.length];

                //concatenate contents of header and body
                System.arraycopy(headerBytes, 0, buf, 0, headerBytes.length);
                System.arraycopy(chunk, 0, buf, headerBytes.length, chunk.length);

                try {
                    dataBackupChannel.sendMessage(buf);
                } catch (IOException e) {
                    //TODO treat
                }

                // wait and process response
                try {
                    Thread.sleep(1000 * counter);
                } catch (InterruptedException e) {
                    //TODO treat
                }
                counter++;
            } while(counter <= 5 && getReplicationDegree(fileId,Integer.toString(chunkNo)) < replicationDegree);
            if(counter > 5) {
                System.out.println("Timed out!");
                System.out.println(getReplicationDegree(fileId,Integer.toString(chunkNo)));
            }
            else{
                System.out.println("Success!");
            }
        };
        new Thread(task).start();
    }

    public void messageHandler(byte[] buffer){
        String data = new String(buffer, 0, buffer.length);
        data = data.trim();
        String[] dataPieces = data.split(CRLF+CRLF);
        String messageHeader[] = dataPieces[0].split(" ");

        //check message type
        String messageType = messageHeader[0];
        String protocolVersion = messageHeader[1];

        switch (messageType){
            case "PUTCHUNK": {
                if(messageHeader.length < 6){
                    System.out.println(messageHeader.length);
                    System.err.println("Not enough fields on header for PUTCHUNK");
                    break;
                }
                String senderID = messageHeader[2];
                if (senderID.equals(this.serverId))  // backup request sent from this peer
                    break;                           // ignore
                printHeader(dataPieces[0], false);
                String fileID = messageHeader[3];
                String chunkNo = messageHeader[4];
                String replicationDegree = messageHeader[5];
                String chunk = dataPieces[1];
                storeChunk(protocolVersion, fileID, chunkNo, replicationDegree, chunk);
                break;
            }
            case "STORED": {
                if(messageHeader.length < 5){
                    System.err.println("Not enough fields on header for STORED");
                    break;
                }
                String senderID = messageHeader[2];
                if (senderID.equals(this.serverId))  // message sent from this peer
                    break;
                printHeader(dataPieces[0],false);
                String fileID = messageHeader[3];
                String chunkNo = messageHeader[4];
                registerStorage(protocolVersion,senderID,fileID,chunkNo);
                break;
            }
            case "GETCHUNK": {
                if(messageHeader.length < 5){
                    System.err.println("Not enough fields on header for GETCHUNK");
                    break;
                }
                String senderID = messageHeader[2];
                if (senderID.equals(this.serverId))  // message sent from this peer
                    break;
                printHeader(dataPieces[0], false);

                String fileID = messageHeader[3];
                String chunkNo = messageHeader[4];

                // if the file doesn't make part of the filesystem, the peer discard the message
                if(!verifyingChunk(fileID,Integer.parseInt(chunkNo))){
                    break;
                }
                putChunk(protocolVersion,senderID,fileID,chunkNo);
            }
            case "CHUNK": {
                if(messageHeader.length < 5){
                    System.err.println("Not enough fields on header for GETCHUNK");
                    break;
                }
                String senderID = messageHeader[2];
                if (senderID.equals(this.serverId))  // message sent from this peer
                    break;
                printHeader(dataPieces[0], false);

                String fileID = messageHeader[3];

                if(!isAFileToRestore(fileID))
                    break;

                String chunkNo = messageHeader[4];
                String chunk = dataPieces[1];

                storeRestoredChunk(fileID,Integer.parseInt(chunkNo),chunk);
            }
            default: {
                //todo treat this??
                break;
            }
        }
    }

    /**
     * Function called when the peer receives a PUTCHUNK message from another peer
     * registers the file and the chunk
     *
     * @param protocolVersion version of the Chunk Backup Subprotocol
     * @param fileID file ID of the file the chunk belongs to
     * @param chunkNo chunk number
     * @param replicationDegree desired file replication degree
     * @param chunk chunk data
     * @return true if the chunk was registered and stored
     */
    private boolean storeChunk(String protocolVersion, String fileID, String chunkNo, String replicationDegree, String chunk){
        if(protocolVersion == null || fileID == null || chunk == null
                || replicationDegree == null || replicationDegree == null
                || chunkNo == null || chunk == null)
            return false;

        byte[] chunkData = chunk.getBytes();
        try {
            // Check if the chunk is already stored
            if(registerChunk(fileID,Integer.parseInt(chunkNo), Integer.parseInt(replicationDegree))) {
                String filename = fileID + "_" + chunkNo;
                FileOutputStream chunkFile = new FileOutputStream(chunksPath + "/" + filename);
                chunkFile.write(chunkData);
            }
            Random random = new Random(System.currentTimeMillis());
            long waitTime = random.nextInt(400);
            Thread.sleep(waitTime);
            String response = makeHeader("STORED",protocolVersion,serverId,fileID,chunkNo);
            registerStorage(protocolVersion,this.serverId,fileID,chunkNo);
            controlChannel.sendMessage(response.getBytes());
        } catch (IOException e) {
            System.err.println("chunk backup subprotocol :: Unable to backup chunk.");
            return false;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return true;
    }

    /**
     * Called when a peer receives a STORED message from another peer
     * It updates the peer's chunkMap to reflect the perceived
     * replication degree of the chunk
     * @param protocolVersion
     * @param senderID
     * @param fileID
     * @param chunkNo
     */
    private boolean registerStorage(String protocolVersion, String senderID, String fileID, String chunkNo){
        if(protocolVersion == null || senderID == null || fileID == null || chunkNo == null)
            return false;

        ArrayList<Integer> chunkPeers = chunkMap.get(fileID+'_'+chunkNo);
        int sender = Integer.parseInt(senderID);
        if(chunkPeers == null){
            chunkPeers = new ArrayList<>();
            chunkPeers.add(sender);
            chunkMap.put(fileID+'_'+chunkNo,chunkPeers);
        } else {
            for (int i = 0; i < chunkPeers.size() ; i++) {
                if(chunkPeers.get(i) == sender) {   // peer was already registered
                    System.out.println("Here");
                    return true;
                }
            }
            chunkPeers.add(sender);
        }

        return true;
    }

    /**
     * Prints the header fields
     * @param header the header string
     * @param sent true if the message is being sent
     */
    private void printHeader(String header, boolean sent){
        System.out.println("Message " + (sent ? "sent" : "received"));
        System.out.println(header);
    }

    /**
     * Creates the message "GETCHUNK" and send its
     * @param fileId id of the file to be restored
     * @param chunkNo Chunk number
     */
    public void requestChunkRestore(String fileId, int chunkNo) {

        Runnable task = () -> {

            String header = makeHeader("GETCHUNK", protocolVersion, serverId, fileId,
                    Integer.toString(chunkNo));

            byte[] headerBytes = header.getBytes();

            try {
                controlChannel.sendMessage(headerBytes);
            } catch (IOException e) {
                e.printStackTrace();
            }

        };
        new Thread(task).start();
    }

    /**
     * Adds a file_id to the restore hash map
     * @param fileId id of the file to be added
     * @return true if the file was added false otherwise
     */
    public boolean addToRestoredHashMap(String fileId){

        if (allRestoredChunks.get(fileId) != null) {
            return false;
        }

        allRestoredChunks.put(fileId,false);

        return true;
    }

    /**
     * Verifies if a given chunk of a given file is stored on the peer
     * @param fileID id of the file
     * @param chunkNo Number of the chunk to be searched
     * @return true if the chunk exists on the filesystem, false otherwise
     */
    public boolean verifyingChunk(String fileID, Integer chunkNo){

        ArrayList<Integer> fileStoredChunks;

        fileStoredChunks=storedChunks.get(fileID);

        if(fileStoredChunks == null || !fileStoredChunks.contains(chunkNo)){
            return false;
        }

        return true;
    }

    private boolean putChunk(String protocolVersion, String senderID, String fileID, String chunkNo) {

        if(protocolVersion == null || senderID == null || fileID == null || chunkNo == null)
            return false;

        String filename = fileID + "_" + chunkNo;
        FileInputStream chunkFile = null;
        try {
            chunkFile = new FileInputStream(chunksPath + "/" + filename);
        } catch (FileNotFoundException e) {
            //TODO treat this
            e.printStackTrace();
        }

        byte[] chunkData = new byte[PeerService.CHUNK_SIZE];

        try {
            chunkFile.read(chunkData);
        } catch (IOException e) {
            //TODO treat this
            e.printStackTrace();
        }

        String header = makeHeader("CHUNK",protocolVersion,serverId,fileID,chunkNo);
        byte[] headerBytes = header.getBytes();

        byte[] buf = new byte[headerBytes.length + chunkData.length];

        //concatenate contents of header and body
        System.arraycopy(headerBytes, 0, buf, 0, headerBytes.length);
        System.arraycopy(chunkData, 0, buf, headerBytes.length, chunkData.length);

        try {
            dataRestoreChannel.sendMessage(buf);
            printHeader(header,true);
        } catch (IOException e) {
            //TODO treat this
            e.printStackTrace();
        }

        //TODO random time uniformly distributed

        return true;
    }

    /**
     * Verifies if allRestoredChunks contains fileID
     * @param fileID id of the file
     * @return true if allRestoredChunks contains fileID, false otherwise
     */
    private boolean isAFileToRestore(String fileID) {

        if(allRestoredChunks.get(fileID) == null)
            return false;

        return true;
    }

    /**
     * Store a restored chunk on the destination file
     * @param fileID id of the file
     * @param chunkNo number of the chunk
     */
    private void storeRestoredChunk(String fileID, int chunkNo, String chunk) {

        String key = fileID + "_" + chunkNo;


        if(!restoredChunks.containsKey(key) && !allRestoredChunks.get(fileID)){
            byte[] chunkData = chunk.getBytes();
            restoredChunks.put(key,chunkData);
            System.out.println("Acabei de colocar : " + key);

            if(fileChunkNum.get(fileID) - 1 == chunkNo){
                allRestoredChunks.put(fileID,true);
            }
        }

    }
    
    public void writeRestoredChunks(String filepath, String fileID) {

        while(!allRestoredChunks.containsKey(fileID)){
        }

        while(!allRestoredChunks.get(fileID)){
        }

        System.out.println("Chegaram todas");

        FileOutputStream chunkFile = null;
        try {
            chunkFile = new FileOutputStream(restoredFilesPath + "/" + filepath, true);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        int numberOfChunks = fileChunkNum.get(fileID);
        int i = 0;
        byte[] chunkData;
        while(i < numberOfChunks){
            chunkData = restoredChunks.get((fileID+"_"+i));
            System.out.println("Vou por o chunk " + i +"  "+ fileID+"_"+i);
            try {
                chunkFile.write(chunkData);
            } catch (IOException e) {
                e.printStackTrace();
            }
            i++;
        }

        System.out.println("Tudo feito!!");
    }
}
