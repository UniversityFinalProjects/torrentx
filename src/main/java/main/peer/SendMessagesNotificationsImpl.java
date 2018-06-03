package main.peer;

import main.App;
import main.TorrentInfo;
import main.downloader.TorrentDownloaders;
import main.peer.peerMessages.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.DataOutputStream;
import java.util.BitSet;

class SendMessagesNotificationsImpl implements SendMessagesNotifications {
    private TorrentInfo torrentInfo;
    private Peer me;
    private Peer peer;
    private Flux<PeerMessage> sentPeerMessagesFlux;
    private FluxSink<PeerMessage> sentMessagesFluxSink;
    private PeerCurrentStatus peerCurrentStatus;
    private SendMessages sendMessages;

    SendMessagesNotificationsImpl(TorrentInfo torrentInfo,
                                  Peer me, Peer peer,
                                  PeerCurrentStatus peerCurrentStatus,
                                  Runnable closeConnectionMethod,
                                  DataOutputStream peerDataOutputStream) {
        this.torrentInfo = torrentInfo;
        this.me = me;
        this.peer = peer;
        this.peerCurrentStatus = peerCurrentStatus;
        this.sentPeerMessagesFlux = Flux.create((FluxSink<PeerMessage> sink) -> this.sentMessagesFluxSink = sink)
                .subscribeOn(App.MyScheduler)
                .publish()
                .autoConnect(0);
        this.sendMessages = new SendMessages(peerDataOutputStream, closeConnectionMethod);
    }

    private Mono<SendMessagesNotifications> send(PeerMessage peerMessage) {
        return peerMessage.sendMessage(this.sendMessages)
                .map(sendMessages -> (SendMessagesNotifications) this)
                .doOnNext(peersCommunicator -> {
                    if (this.sentMessagesFluxSink != null)
                        this.sentMessagesFluxSink.next(peerMessage);
                });
    }

    @Override
    public Mono<SendMessagesNotifications> sendPieceMessage(PieceMessage pieceMessage) {
        return send(pieceMessage)
                .flatMap(pieceEvent -> TorrentDownloaders.getAllocatorStore()
                        .free(pieceMessage.getAllocatedBlock())
                        .map(__ -> pieceEvent))
                .doOnError(throwable -> TorrentDownloaders.getAllocatorStore()
                        .free(pieceMessage.getAllocatedBlock())
                        .publishOn(Schedulers.elastic())
                        .block())
                .doOnCancel(() -> TorrentDownloaders.getAllocatorStore()
                        .free(pieceMessage.getAllocatedBlock())
                        .publishOn(Schedulers.elastic())
                        .block())
                .doOnNext(sendPeerMessages -> this.peerCurrentStatus.updatePiecesStatus(pieceMessage.getIndex()));
    }

    @Override
    public Mono<SendMessagesNotifications> sendBitFieldMessage(BitSet completedPieces) {
        return send(new BitFieldMessage(this.getMe(), this.getPeer(), completedPieces))
                .doOnNext(sendPeerMessages -> this.peerCurrentStatus.updatePiecesStatus(completedPieces));
    }

    @Override
    public Mono<SendMessagesNotifications> sendCancelMessage(int index, int begin, int blockLength) {
        return send(new CancelMessage(this.getMe(), this.getPeer(), index, begin, blockLength));
    }

    @Override
    public Mono<SendMessagesNotifications> sendChokeMessage() {
        return send(new ChokeMessage(this.getMe(), this.getPeer()))
                .doOnNext(__ -> this.peerCurrentStatus.setAmIChokingHim(true));
    }

    @Override
    public Mono<SendMessagesNotifications> sendHaveMessage(int pieceIndex) {
        return send(new HaveMessage(this.getMe(), this.getPeer(), pieceIndex))
                .doOnNext(sendPeerMessages -> this.peerCurrentStatus.updatePiecesStatus(pieceIndex));
    }

    @Override
    public Mono<SendMessagesNotifications> sendInterestedMessage() {
        return send(new InterestedMessage(this.getMe(), this.getPeer()))
                .doOnNext(__ -> this.peerCurrentStatus.setAmIInterestedInHim(true));
    }

    @Override
    public Mono<SendMessagesNotifications> sendKeepAliveMessage() {
        return send(new KeepAliveMessage(this.getMe(), this.getPeer()));
    }

    @Override
    public Mono<SendMessagesNotifications> sendNotInterestedMessage() {
        return send(new NotInterestedMessage(this.getMe(), this.getPeer()))
                .doOnNext(__ -> this.peerCurrentStatus.setAmIInterestedInHim(false));
    }

    @Override
    public Mono<SendMessagesNotifications> sendPortMessage(short listenPort) {
        return send(new PortMessage(this.getMe(), this.getPeer(), listenPort));
    }

    @Override
    public Mono<SendMessagesNotifications> sendRequestMessage(int index, int begin, int blockLength) {
        int pieceLength = this.torrentInfo.getPieceLength(index);
        return TorrentDownloaders.getAllocatorStore()
                .createRequestMessage(this.getMe(), this.getPeer(), index, begin, blockLength, pieceLength)
                .flatMap(this::send);
    }

    @Override
    public Mono<SendMessagesNotifications> sendUnchokeMessage() {
        return send(new UnchokeMessage(this.getMe(), this.getPeer()))
                .doOnNext(__ -> this.peerCurrentStatus.setAmIChokingHim(false));
    }

    @Override
    public Flux<PeerMessage> sentPeerMessagesFlux() {
        return this.sentPeerMessagesFlux;
    }

    private Peer getPeer() {
        return peer;
    }

    private Peer getMe() {
        return me;
    }
}
