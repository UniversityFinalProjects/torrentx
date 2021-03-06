package main.peer.algorithms;

import main.AppConfig;
import main.HexByteConverter;
import main.TorrentInfo;
import main.allocator.AllocatorStore;
import main.download.manager.TorrentDownloaders;
import main.peer.Link;
import main.peer.Peer;
import main.peer.exceptions.PeerExceptions;
import main.peer.peerMessages.HandShake;
import main.peer.peerMessages.PeerMessage;
import main.tracker.BadResponseException;
import main.tracker.TrackerConnection;
import main.tracker.response.AnnounceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.AbstractMap;

public class PeersProvider {
    private static Logger logger = LoggerFactory.getLogger(PeersProvider.class);
    private static Scheduler connectToPeersScheduler = Schedulers.newParallel("CONNECT-PEERS", 4);
    private TorrentInfo torrentInfo;
    private AllocatorStore allocatorStore;
    private String identifier;
    private FluxSink<AbstractMap.SimpleEntry<Link, PeerMessage>> emitIncomingPeerMessages;

    public PeersProvider(AllocatorStore allocatorStore, TorrentInfo torrentInfo, String identifier,
                         FluxSink<AbstractMap.SimpleEntry<Link, PeerMessage>> emitIncomingPeerMessages) {
        this.identifier = identifier;
        this.torrentInfo = torrentInfo;
        this.allocatorStore = allocatorStore;
        this.emitIncomingPeerMessages = emitIncomingPeerMessages;
    }

    public Mono<Link> connectToPeerMono(Peer peer) {
        return Mono.create((MonoSink<Link> sink) -> {
            Socket peerSocket = new Socket();
            sink.onCancel(() -> {
                try {
                    peerSocket.close();
                } catch (IOException e) {
                    logger.error("fatal error while closing a socket: ", e);
                }
                logger.debug("closed a socket because the connection is no longer needed or for any other reason.: " + peerSocket);
            });
            try {
                peerSocket.connect(new InetSocketAddress(peer.getPeerIp(), peer.getPeerPort()), 1000 * 10);
                DataInputStream receiveMessages = new DataInputStream(peerSocket.getInputStream());
                DataOutputStream sendMessages = new DataOutputStream(peerSocket.getOutputStream());

                // firstly, we need to send Handshake message to the peer and receive Handshake back.
                HandShake handShakeSending = new HandShake(HexByteConverter.hexToByte(this.torrentInfo.getTorrentInfoHash()), AppConfig.getInstance().getPeerId().getBytes());
                sendMessages.write(handShakeSending.createPacketFromObject());
                HandShake handShakeReceived = new HandShake(receiveMessages);
                String receivedTorrentInfoHash = HexByteConverter.byteToHex(handShakeReceived.getTorrentInfoHash());
                if (!this.torrentInfo.getTorrentInfoHash().toLowerCase().equals(receivedTorrentInfoHash.toLowerCase())) {
                    // the peer sent me invalid HandShake message.
                    // by the p2p spec, I need to close to the socket.
                    sendMessages.close();
                    peerSocket.close();
                    sink.error(new BadResponseException("we sent the peer a handshake request" +
                            " and he sent us back handshake response" +
                            " with the wrong torrent-info-hash: " + receivedTorrentInfoHash));
                } else {
                    // all went well, I accept this connection.
                    Link link = new Link(this.allocatorStore, this.torrentInfo, peer, peerSocket, receiveMessages, sendMessages, this.identifier, emitIncomingPeerMessages);
                    sink.success(link);
                }
            } catch (IOException e) {
                try {
                    peerSocket.close();
                } catch (IOException e1) {
                    // TODO: do something with this shit
                    logger.error("fatal error while closing a socket: ", e1);
                }
                logger.trace("closed a socket: ", e);
                sink.error(e);
            }
        }).subscribeOn(connectToPeersScheduler)
                .doOnNext(link -> logger.debug("connected to peer successfully: " + link))
                .doOnError(PeerExceptions.communicationErrors, throwable -> logger.debug("error signal: (the application failed to connect to a peer." +
                        " the application will try to connect to the next available peer).\n" +
                        "peer: " + peer.toString() + "\n" +
                        "error message: " + throwable.getMessage() + ".\n" +
                        "error type: " + throwable.getClass().getName()))
                .onErrorResume(PeerExceptions.communicationErrors, error -> Mono.empty());
    }

    private Flux<Peer> connectToPeers$(TrackerConnection trackerConnection) {
        return TorrentDownloaders.getListener()
                .getListeningPort()
                .flatMap(listeningPort -> trackerConnection.announceMono(torrentInfo.getTorrentInfoHash(), listeningPort))
                .flatMapMany(AnnounceResponse::getPeersFlux);
    }

    public Flux<Link> connectToPeers$(Flux<TrackerConnection> trackerConnectionFlux) {
        return trackerConnectionFlux.flatMap(this::connectToPeers$)
                .distinct()
                .flatMap(this::connectToPeerMono);
    }

    public TorrentInfo getTorrentInfo() {
        return torrentInfo;
    }
}
