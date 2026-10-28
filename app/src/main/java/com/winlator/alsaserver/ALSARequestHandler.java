package com.winlator.alsaserver;

import com.winlator.sysvshm.SysVSharedMemory;
import com.winlator.xconnector.ConnectedClient;
import com.winlator.xconnector.RequestHandler;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ALSARequestHandler implements RequestHandler {
    private int maxSHMemoryId = 0;

    @Override
    public boolean handleRequest(ConnectedClient client) throws IOException {
        ALSAClient alsaClient = (ALSAClient)client.getTag();
        XInputStream inputStream = client.getInputStream();
        XOutputStream outputStream = client.getOutputStream();

        if (inputStream.available() < 5) return false;
        byte requestCode = inputStream.readByte();
        int requestLength = inputStream.readInt();
        if (requestLength < 0 || requestLength > ALSAClient.MAX_BUFFER_BYTES)
            throw new IOException("Invalid ALSA request length");

        switch (requestCode) {
            case RequestCodes.CLOSE:
                alsaClient.release();
                break;
            case RequestCodes.START:
                alsaClient.start();
                break;
            case RequestCodes.STOP:
                alsaClient.stop();
                break;
            case RequestCodes.PAUSE:
                alsaClient.pause();
                break;
            case RequestCodes.PREPARE:
                if (requestLength != 10) throw new IOException("Invalid ALSA PREPARE length");
                if (inputStream.available() < requestLength) return false;

                int prepareChannels = Byte.toUnsignedInt(inputStream.readByte());
                ALSAClient.DataType prepareType = readDataType(inputStream);
                int prepareRate = inputStream.readInt();
                int prepareFrames = inputStream.readInt();
                if (!ALSAClient.validBuffer(prepareFrames, prepareChannels, prepareType, prepareRate))
                    throw new IOException("Invalid ALSA PCM buffer");
                alsaClient.setChannels(prepareChannels);
                alsaClient.setDataType(prepareType);
                alsaClient.setSampleRate(prepareRate);
                alsaClient.setBufferSize(prepareFrames);
                alsaClient.prepare();

                if (ALSAClient.USE_SHARED_MEMORY) createSharedMemory(alsaClient, outputStream);
                break;
            case RequestCodes.WRITE:
                ByteBuffer sharedBuffer = alsaClient.getSharedBuffer();
                if (sharedBuffer != null) {
                    copySharedBuffer(alsaClient, requestLength, outputStream);
                    alsaClient.writeDataToTrack(alsaClient.getAuxBuffer());
                    sharedBuffer.putInt(0, alsaClient.pointer());
                }
                else {
                    if (inputStream.available() < requestLength) return false;
                    alsaClient.writeDataToTrack(inputStream.readByteBuffer(requestLength));
                }
                break;
            case RequestCodes.DRAIN:
                alsaClient.drain();
                break;
            case RequestCodes.POINTER:
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeInt(alsaClient.pointer());
                }
                break;
            case RequestCodes.MIN_BUFFER_SIZE:
                if (requestLength != 6) throw new IOException("Invalid ALSA MIN_BUFFER_SIZE length");
                if (inputStream.available() < requestLength) return false;
                int channels = Byte.toUnsignedInt(inputStream.readByte());
                ALSAClient.DataType dataType = readDataType(inputStream);
                int sampleRate = inputStream.readInt();
                if (!ALSAClient.validFormat(channels, dataType, sampleRate))
                    throw new IOException("Invalid ALSA PCM format");
                int minBufferSize = ALSAClient.latencyMillisToBufferSize(alsaClient.options.latencyMillis, channels, dataType, sampleRate);

                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeInt(minBufferSize);
                }
                break;
            default:
                throw new IOException("Unknown ALSA request");
        }
        return true;
    }

    private ALSAClient.DataType readDataType(XInputStream inputStream) throws IOException {
        int type = Byte.toUnsignedInt(inputStream.readByte());
        if (type >= ALSAClient.DataType.values().length) throw new IOException("Invalid ALSA PCM encoding");
        return ALSAClient.DataType.values()[type];
    }

    private void copySharedBuffer(ALSAClient alsaClient, int requestLength, XOutputStream outputStream) throws IOException {
        ByteBuffer sharedBuffer = alsaClient.getSharedBuffer();
        ByteBuffer auxBuffer = alsaClient.getAuxBuffer();
        if (auxBuffer == null || requestLength > auxBuffer.capacity()
                || requestLength > sharedBuffer.capacity() - ALSAClient.BUFFER_OFFSET)
            throw new IOException("Invalid ALSA shared write length");

        // Reset limits first: the preceding write can have been shorter than this one.
        auxBuffer.clear().limit(requestLength);
        sharedBuffer.clear().position(ALSAClient.BUFFER_OFFSET).limit(ALSAClient.BUFFER_OFFSET + requestLength);
        auxBuffer.put(sharedBuffer);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte)1);
        }
    }

    private void createSharedMemory(ALSAClient alsaClient, XOutputStream outputStream) throws IOException {
        int shmSize = alsaClient.getBufferSizeInBytes() + ALSAClient.BUFFER_OFFSET;
        int fd = SysVSharedMemory.createMemoryFd("alsa-shm"+(++maxSHMemoryId), shmSize);

        if (fd >= 0) {
            ByteBuffer buffer = SysVSharedMemory.mapSHMSegment(fd, shmSize, 0, false);
            if (buffer != null) alsaClient.setSharedBuffer(buffer);
        }

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte)0);
            outputStream.setAncillaryFd(fd);
        }
        finally {
            if (fd >= 0) XConnectorEpoll.closeFd(fd);
        }
    }
}
