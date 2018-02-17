package com.steps;

import com.utils.*;
import cucumber.api.java.Before;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;
import main.TorrentInfo;
import main.peer.InitializePeersCommunication;
import main.peer.Peer;
import main.peer.PeersCommunicator;
import main.peer.PeersProvider;
import main.peer.peerMessages.PeerMessage;
import main.tracker.BadResponseException;
import main.tracker.Tracker;
import main.tracker.TrackerConnection;
import main.tracker.TrackerProvider;
import main.tracker.response.TrackerResponse;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.SocketTimeoutException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.mockito.Mockito.mock;

public class MyStepdefs {

    private String torrentFilePath;

    private TorrentInfo torrentInfo = mock(TorrentInfo.class);

    private List<RemoteFakePeer> remoteFakePeers;

    @Before
    public void beforeTest() {
        Hooks.onErrorDropped(throwable -> {
        });
    }

    @Given("^new torrent file: \"([^\"]*)\".$")
    public void newTorrentFile(String torrentFileName) throws Throwable {
        this.torrentFilePath = torrentFileName;
        TorrentInfo torrentInfo = Utils.readTorrentFile(torrentFileName);

        Mockito.when(this.torrentInfo.getTorrentInfoHash())
                .thenReturn(torrentInfo.getTorrentInfoHash());
        Mockito.when(this.torrentInfo.getTrackerList())
                .thenReturn(torrentInfo.getTrackerList());
    }

    @Given("^additional not-responding trackers to the tracker-list.$")
    public void additionalNotRespondingTrackersToTheTrackerListFromFile() throws Throwable {
        TorrentInfo torrentInfo = Utils.readTorrentFile(this.torrentFilePath);
        String torrentHashInfo = torrentInfo.getTorrentInfoHash();
        torrentInfo.getTrackerList()
                .stream()
                .findFirst()
                .ifPresent(tracker -> {
                    List<Tracker> fakeTrackers = Arrays.asList(
                            new Tracker("wrongUrl.com", 8090), // wrong url (but valid url) and a random port
                            new Tracker(tracker.getTracker(), tracker.getPort() + 1) // wrong port
                    );
                    List<Tracker> trackers = new LinkedList<>();
                    trackers.addAll(fakeTrackers);
                    trackers.addAll(torrentInfo.getTrackerList());
                    trackers.addAll(fakeTrackers);

                    // define our mock object
                    Mockito.when(this.torrentInfo.getTorrentInfoHash()).thenReturn(torrentHashInfo);
                    Mockito.when(this.torrentInfo.getTrackerList()).thenReturn(trackers);
                });
    }

    @Given("^additional invalid url of a tracker.$")
    public void additionalInvalidUrlOfATrackerOf() throws Throwable {
        TorrentInfo torrentInfo = Utils.readTorrentFile(this.torrentFilePath);
        String torrentHashInfo = torrentInfo.getTorrentInfoHash();
        Mockito.when(this.torrentInfo.getTorrentInfoHash())
                .thenReturn(torrentHashInfo);
        Mockito.when(this.torrentInfo.getTrackerList())
                .thenReturn(Collections.singletonList(new Tracker("invalid.url.123", 123)));
    }

    @Given("^new torrent file: \"([^\"]*)\" containing the following fake peers:$")
    public void newTorrentFileContainingTheFollowingFakePeers(String torrentFilePath, List<Peer> peers) throws Throwable {
        this.remoteFakePeers = peers.stream()
                .map(RemoteFakePeer::new)
                .peek(RemoteFakePeer::listen)
                .collect(Collectors.toList());
    }

    @Then("^application send and receive Handshake from the same random peer.$")
    public void applicationSendAndReceiveHandshakeFromTheSameRandomPeer() throws Throwable {
        Mono<PeersCommunicator> connectedPeerMono =
                TrackerProvider.connectToTrackers(this.torrentInfo.getTrackerList())
                        .flatMap((TrackerConnection trackerConnection) -> PeersProvider.connectToPeers(trackerConnection, this.torrentInfo.getTorrentInfoHash()))
                        .doOnEach(x -> System.out.println("1 " + x))
                        .take(1)
                        .doOnEach(x -> System.out.println("2 " + x))
                        .single()
                        .cache();

        StepVerifier.create(connectedPeerMono)
                .expectNextCount(1)
                .expectComplete()
                .verify();

        connectedPeerMono.subscribe(PeersCommunicator::closeConnection);
    }

    @Then("^application send and receive the following messages from a random tracker:$")
    public void applicationSendAndReceiveTheFollowingMessagesFromARandomTracker(List<TrackerFakeRequestResponseMessage> messages) throws Throwable {

        if (messages.stream()
                .noneMatch(fakeMessage -> fakeMessage.getTrackerRequestType() == TrackerRequestType.Connect))
            throw new IllegalArgumentException("messages list must contain `connect` request" +
                    " (we are not using it in the tests but " +
                    "it should be there before any other request).");

        // if I get an errorSignal signal containing one of those errors,
        // then I will communicate with the next tracker in the tracker-list.
        Predicate<Throwable> communicationErrorsToIgnore = (Throwable error) ->
                error instanceof SocketTimeoutException ||
                        error instanceof BadResponseException;

        Flux<TrackerResponse> actualTrackerResponseFlux =
                TrackerProvider.connectToTrackers(this.torrentInfo.getTrackerList())
                        .flatMap(trackerConnection ->
                                Flux.fromIterable(messages)
                                        .filter(fakeMessage -> fakeMessage.getTrackerRequestType() != TrackerRequestType.Connect)
                                        // given a tracker, communicate with him and get the signal containing the response.
                                        .flatMap(messageWeNeedToSend -> {
                                            switch (messageWeNeedToSend.getTrackerRequestType()) {
                                                case Announce:
                                                    return trackerConnection.announce(this.torrentInfo.getTorrentInfoHash());
                                                case Scrape:
                                                    return trackerConnection.scrape(Collections.singletonList(this.torrentInfo.getTorrentInfoHash()));
                                                default:
                                                    throw new IllegalArgumentException(messageWeNeedToSend.getTrackerRequestType().toString());
                                            }
                                        }).onErrorResume(communicationErrorsToIgnore, error -> Mono.empty()))
                        // unimportant note: take is an operation which send "cancel"
                        // signal if the flux contain more elements then we want.
                        .take(messages.size() - 1);

        // check the responses
        // check if there is an **expected** error signal:
        Optional<TrackerFakeRequestResponseMessage> expectedErrorSignal = messages
                .stream()
                .filter(message -> message.getErrorSignalType() != null)
                .findAny();

        if (expectedErrorSignal.isPresent())
            StepVerifier.create(actualTrackerResponseFlux)
                    .expectError(expectedErrorSignal.get().getErrorSignalType().getErrorSignal())
                    .verify();
        else
            StepVerifier.create(actualTrackerResponseFlux)
                    .expectNextCount(messages.size() - 1)
                    .expectComplete()
                    .verify();
    }

    @Then("^application send to \\[peer ip: \"([^\"]*)\", peer port: \"([^\"]*)\"\\] and receive the following messages:$")
    public void applicationSendToPeerIpPeerPortAndReceiveTheFollowingMessages(String peerIp, int peerPort,
                                                                              List<PeerFakeRequestResponse> peerFakeRequestResponses) throws Throwable {

        RemoteFakePeer remoteFakePeer = new RemoteFakePeer(new Peer(peerIp, peerPort));
        remoteFakePeer.listen();

        Mono<PeersCommunicator> peersCommunicatorMono =
                InitializePeersCommunication.getInstance()
                        .connectToPeer(this.torrentInfo.getTorrentInfoHash(), remoteFakePeer)
                        .cache();

        // send all messages
        Flux<Void> peerRequestsFlux = Flux.fromIterable(peerFakeRequestResponses)
                .map(PeerFakeRequestResponse::getSendMessageType)
                .flatMap(peerRequestMessage ->
                        peersCommunicatorMono.flux()
                                .flatMap(peersCommunicator -> Utils.sendFakeMessage(peerRequestMessage, peersCommunicator)));

        // check that all the messages sent successfully.
        // note: Mono<Void> never signal onNext. Only error or complete.
        StepVerifier.create(peerRequestsFlux)
                .verifyComplete();

        // receive all responses from peers.
        Flux<PeerMessage> peersResponses = peersCommunicatorMono.flux()
                .flatMap(PeersCommunicator::receive);

        // check if we expect an error signal.
        Optional<ErrorSignalType> errorSignalType =
                peerFakeRequestResponses
                        .stream()
                        .filter(peerFakeRequestResponse -> peerFakeRequestResponse.getErrorSignalType() != null)
                        .map(PeerFakeRequestResponse::getErrorSignalType)
                        .findAny();

        // check if we expect a complete signal
        Optional<PeerFakeRequestResponse> completeSignal =
                peerFakeRequestResponses
                        .stream()
                        .filter(peerFakeRequestResponse -> peerFakeRequestResponse.getErrorSignalType() == null &&
                                peerFakeRequestResponse.getReceiveMessageType() == null)
                        .findAny();

        if (completeSignal.isPresent())
            StepVerifier.create(peersResponses)
                    .expectNextCount(peerFakeRequestResponses.size() - 1)
                    .expectComplete()
                    .verify();
        else if (errorSignalType.isPresent())
            StepVerifier.create(peersResponses)
                    .expectNextCount(peerFakeRequestResponses.size() - 1)
                    .expectError(errorSignalType.get().getErrorSignal())
                    .verify();
        else
            StepVerifier.create(peersResponses.take(peerFakeRequestResponses.size()))
                    .expectNextCount(peerFakeRequestResponses.size())
                    .verifyComplete();

        peersCommunicatorMono.subscribe(PeersCommunicator::closeConnection);
        remoteFakePeer.shutdown();
    }
}

