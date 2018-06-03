package main.peer;

import main.TorrentInfo;
import main.downloader.TorrentDownloaders;
import main.peer.peerMessages.*;
import reactor.core.publisher.Mono;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Objects;

// TODO: implement visitor.
public class PeerMessageFactory {
    public static Mono<? extends PeerMessage> waitForMessage(TorrentInfo torrentInfo, Peer from, Peer to, DataInputStream dataInputStream) {
        // lengthOfTheRestOfData == messageLength == how much do we need to read more
        int lengthOfTheRestOfData;
        try {
            lengthOfTheRestOfData = dataInputStream.readInt();
        } catch (IOException e) {
            return Mono.error(e);
        }

        if (lengthOfTheRestOfData == 0) {
            byte keepAliveMessageId = 10;
            PeerMessage peerMessage = createMessage(torrentInfo, from, to, keepAliveMessageId, new byte[0]);
            return Mono.just(peerMessage);
        }
        byte messageId;
        try {
            messageId = dataInputStream.readByte();
        } catch (IOException e) {
            return Mono.error(e);
        }

        int messagePayloadLength = lengthOfTheRestOfData - 1;

        if (messageId == PeerMessageId.pieceMessage.getMessageId())
            return createPieceMessage(torrentInfo, from, to, messagePayloadLength, dataInputStream);

        byte[] messagePayloadByteArray = new byte[messagePayloadLength];
        try {
            dataInputStream.readFully(messagePayloadByteArray);
        } catch (IOException e) {
            return Mono.error(e);
        }

        if (messageId == PeerMessageId.requestMessage.getMessageId())
            return createRequestMessage(torrentInfo, from, to, messageId,
                    messagePayloadByteArray);

        PeerMessage peerMessage = createMessage(torrentInfo, from, to, messageId, messagePayloadByteArray);
        return Mono.just(peerMessage);
    }

    public static Mono<? extends PeerMessage> createPieceMessage(TorrentInfo torrentInfo, Peer from, Peer to,
                                                                 int messagePayloadLength, DataInputStream dataInputStream) {
        int index;
        try {
            index = dataInputStream.readInt();
        } catch (IOException e) {
            return Mono.error(e);
        }
        int pieceLength = torrentInfo.getPieceLength(index);
        int begin;
        try {
            begin = dataInputStream.readInt();
        } catch (IOException e) {
            return Mono.error(e);
        }
        int blockLength = messagePayloadLength - 8;// 8 == 'index' length in bytes + 'begin' length in bytes
        PieceMessage result = TorrentDownloaders.getAllocatorStore()
                .createPieceMessage(from, to, index, begin, blockLength, pieceLength)
                .flatMap(pieceMessage -> {
                    try {
                        dataInputStream.readFully(pieceMessage.getAllocatedBlock().getBlock(),
                                pieceMessage.getAllocatedBlock().getOffset(),
                                pieceMessage.getAllocatedBlock().getLength());
                        return Mono.just(pieceMessage);
                    } catch (IOException e) {
                        return Mono.error(e);
                    }
                    // TODO: remove the block() operator. we have a bug because
                    // if we remove the block(), than sometimes I can't
                    // readFully anything. Its like someone is reading
                    // while I read.
                }).block();
        return Mono.just(result);
    }

    public static Mono<? extends PeerMessage> createRequestMessage(TorrentInfo torrentInfo, Peer from, Peer to, byte messageId,
                                                                   byte[] payload) {
        assert messageId == PeerMessageId.requestMessage.getMessageId();

        ByteBuffer wrap = ByteBuffer.wrap(payload);
        int index = wrap.getInt();
        int begin = wrap.getInt();
        int blockLength = wrap.getInt();

        return TorrentDownloaders.getAllocatorStore()
                .createRequestMessage(from, to, index, begin, blockLength,
                        torrentInfo.getPieceLength(index));
    }

    public static PeerMessage createMessage(TorrentInfo torrentInfo, Peer from, Peer to, byte messageId,
                                            byte[] payload) {
        PeerMessageId peerMessageId = PeerMessageId.fromValue(messageId);
        switch (Objects.requireNonNull(peerMessageId)) {
            case bitFieldMessage:
                return new BitFieldMessage(from, to, BitSet.valueOf(payload));
            case cancelMessage: {
                ByteBuffer wrap = ByteBuffer.wrap(payload);
                int index = wrap.getInt();
                int begin = wrap.getInt();
                int blockLength = wrap.getInt();
                return new CancelMessage(from, to, index, begin, blockLength);
            }
            case chokeMessage:
                return new ChokeMessage(from, to);
            case haveMessage:
                int pieceIndex = ByteBuffer.wrap(payload).getInt();
                return new HaveMessage(from, to, pieceIndex);
            case interestedMessage:
                return new InterestedMessage(from, to);
            case keepAliveMessage:
                return new KeepAliveMessage(from, to);
            case notInterestedMessage:
                return new NotInterestedMessage(from, to);
            case portMessage: {
                ByteBuffer wrap = ByteBuffer.wrap(payload);
                short portNumber = wrap.getShort();
                return new PortMessage(from, to, portNumber);
            }
            case unchokeMessage:
                return new UnchokeMessage(from, to);
            case extendedMessage:
                return new ExtendedMessage(from, to);
            default:
                throw new IllegalArgumentException("illegal message-id: " + messageId);
        }
    }
}
