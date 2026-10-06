package dev.deadinternet.classification;

import dev.deadinternet.model.Account;

import java.util.List;

/** JEV request builders for classification tests. */
final class Requests {

    private Requests() {}

    static JevClassificationRequest request(Account account, double maxSimilarity, boolean duplicate, int within60,
                                            int coordinatedAccounts, double maxCoordination, int clusterSize, int phraseAccounts) {
        return new JevClassificationRequest(account,
                new JevClassificationRequest.ReplyContext("Exactly this. People need to wake up.", 41),
                new JevClassificationRequest.Features(0.9, maxSimilarity, clusterSize > 1 ? 0.85 : 0, Math.min(within60, 3),
                        within60, clusterSize, 1, duplicate, within60 >= 2, null, 0,
                        account.followerFollowingRatio(), account.postsPerDay(),
                        phraseAccounts > 0 ? List.of("people need to wake up") : List.of(), phraseAccounts,
                        coordinatedAccounts, maxCoordination),
                List.of("Exactly this. Wake up people."), List.of());
    }
}
