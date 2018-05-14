package main.algorithms.impls;

import main.TorrentInfo;
import main.algorithms.*;
import main.algorithms.impls.v1.download.*;
import main.algorithms.impls.v1.notification.NotifyAboutCompletedPieceAlgorithmImpl;
import main.algorithms.impls.v1.upload.UploadAlgorithmImpl;
import main.file.system.FileSystemLink;
import main.peer.Link;
import main.torrent.status.TorrentStatusStore;
import reactor.core.publisher.Flux;

public class BittorrentAlgorithmInitializer {
    public static BittorrentAlgorithm v1(TorrentInfo torrentInfo,
                                         TorrentStatusStore torrentStatusStore,
                                         FileSystemLink fileSystemLink,
                                         Flux<Link> peersCommunicatorFlux) {
        Flux<Link> recordedPeerFlux = peersCommunicatorFlux
                .flatMap(peersCommunicator ->
                        peersCommunicator.sendMessages().sendInterestedMessage()
                                .map(sendPeerMessages -> peersCommunicator))
                .replay()
                .autoConnect();

        NotifyAboutCompletedPieceAlgorithm notifyAboutCompletedPieceAlgorithm =
                new NotifyAboutCompletedPieceAlgorithmImpl(torrentInfo,
                        torrentStatusStore,
                        fileSystemLink,
                        recordedPeerFlux);

        UploadAlgorithm uploadAlgorithm = new UploadAlgorithmImpl(torrentInfo,
                torrentStatusStore,
                fileSystemLink,
                peersCommunicatorFlux);

        PeersToPiecesMapper peersToPiecesMapper =
                new PeersToPiecesMapperImpl(recordedPeerFlux,
                        fileSystemLink.getUpdatedPiecesStatus());

        BlockDownloader blockDownloader = new BlockDownloaderImpl(torrentInfo, fileSystemLink);

        PiecesDownloader piecesDownloader = new PiecesDownloaderImpl(torrentInfo, torrentStatusStore,
                fileSystemLink, peersToPiecesMapper, blockDownloader);

        DownloadAlgorithm downloadAlgorithm = new DownloadAlgorithm(piecesDownloader, blockDownloader, peersToPiecesMapper);

        return new BittorrentAlgorithm(uploadAlgorithm, downloadAlgorithm, notifyAboutCompletedPieceAlgorithm);
    }
}
