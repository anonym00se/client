package com.haxalicious.ChunkComparePlugin; // Package must be the same as plugin because I'm too lazy to fix it right now

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface IChunkParser extends Remote {
    void queueChunk(int[] chunkPos) throws RemoteException; // Kind of lazy way to represent ChunkPos, not super janky tho
    Object[] getParsedChunk() throws RemoteException; // Probably could cast shit but tbh I'm too lazy
}