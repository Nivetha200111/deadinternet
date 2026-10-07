package dev.deadinternet.analysis;

import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Measures {@link TextSignals} over an account's posts. Deterministic, so the same text always yields the same evidence. */
@Service
public class TextSignalService {

    private static final Pattern URGENCY = Pattern.compile("\\b(hurry|immediately|urgent(ly)?|act now|asap|limited time"
            + "|last chance|today only|don'?t miss( out)?|before it sells out|sells out|only \\d+ (minutes?|hours?|spots?|left)( left)?"
            + "|kindly|locked|suspended|verify your|claim (your|now))\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("(https?://\\S+|www\\.\\S+|\\b[a-z0-9-]+\\.(com|net|io|co|xyz|me|ly|gg|app|link|site|shop|org)(/\\S*)?)",
            Pattern.CASE_INSENSITIVE);
    // t.co and lnkd.in are left out: X and LinkedIn wrap every link in them, so they say nothing about the poster.
    private static final Pattern SHORTENER = Pattern.compile("\\b(bit\\.ly|tinyurl\\.com|goo\\.gl|ow\\.ly|is\\.gd|buff\\.ly|cutt\\.ly"
            + "|rb\\.gy|shorturl\\.at|tiny\\.cc|rebrand\\.ly|t\\.ly|s\\.id)\\b", Pattern.CASE_INSENSITIVE);
    // A contact, not a mention: "Telegram announced..." in a news post is not a fake-support reply.
    private static final Pattern MESSAGING = Pattern.compile("(\\b(t\\.me|wa\\.me)/\\S+"
            + "|\\b(contact|message|text|reach|dm|chat)\\b[^.\\n]{0,30}\\b(telegram|whatsapp)\\b"
            + "|\\b(telegram|whatsapp)\\s*(:|@|at\\b|\\+?\\d)"
            + "|\\bsend (a|me a) (direct message|dm)\\b|\\bdm (me|them|him|her)\\b|\\bcontact (the )?support\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CRYPTO = Pattern.compile("(\\$[A-Z]{2,6}\\b|\\b(airdrop|wallet|seed phrase|metamask|trust wallet"
            + "|giveaway|giving away|connect your wallet|double rewards|presale|token launch|whitelist|mint)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MENTION = Pattern.compile("(?<![\\w@])@\\w{2,}");
    // Thread counters start at 1 ("1/10", "[1/10]"); other n/m pairs are usually dates.
    private static final Pattern THREAD_HOOK = Pattern.compile("(🧵|👇|[\\[(]1/\\d{1,2}[\\])]|\\b1/\\d{1,2}\\s*$"
            + "|so you don'?t have to|unpopular opinion|here'?s how|a thread\\b|bookmark this)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    // Profit and recovery claims. "made $340" alone also fits a bake sale, so the heuristic only weighs it heavily
    // together with a mention or an off-platform contact.
    private static final Pattern MONEY = Pattern.compile("(\\b(made|earned|making|profited|withdrew)\\s+\\$\\s?\\d[\\d,.]*\\s?k?\\b"
            + "|\\$\\s?\\d[\\d,.]*\\s?k?\\s+(in|within)\\s+(just\\s+)?\\d+\\s+(hours?|days?|weeks?)"
            + "|\\b(guaranteed (returns?|profits?)|trading signals?|signal group|account manager|forex|binary options"
            + "|helped me (recover|get (it|my \\w+) back)|recover(ed)? (my|your|all my|lost|stolen) (funds|crypto|money|account|btc|bitcoin)"
            + "|thanks to @\\w+,? i (made|earned|recovered|got))\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOB = Pattern.compile("(#interested\\b|\\b(work from home|work-from-home"
            + "|earn \\$\\s?\\d[\\d,]*\\s?(/|per|a|an)\\s?(day|week|hour)|comment (your )?(email|\"?interested\"?)"
            + "|no experience (needed|required)|urgent(ly)? hiring|daily pay(ment)?|part[- ]time job)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ADULT = Pattern.compile("(🔞|\\b(check (out )?my (bio|profile)|link in (my )?bio"
            + "|lonely tonight|horny|nudes|onlyfans|hot pics|private pics|dm me for fun|hook ?up)\\b)",
            Pattern.CASE_INSENSITIVE);
    /** Profile pointers that creators use legitimately too; on their own they are weak evidence. */
    public static final List<String> BIO_POINTERS = List.of("check my bio", "check out my bio", "check my profile",
            "check out my profile", "link in bio", "link in my bio");
    private static final Pattern FOLLOW = Pattern.compile("(#(f4f|followback|follow4follow)\\b|\\b(follow for follow"
            + "|follow 4 follow|i follow back|follow back|gain \\d+\\+? followers|buy (real )?followers|free followers)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRAISE = Pattern.compile("\\b(great|amazing|awesome|insightful|valuable|well said|so true"
            + "|spot on|thanks for sharing|love this|brilliant|powerful|inspiring|helpful|informative)\\b",
            Pattern.CASE_INSENSITIVE);
    /** Words a generic comment is made of: praise, thanks and filler that fit under any post. */
    private static final java.util.Set<String> GENERIC_WORDS = java.util.Set.of("great", "insights", "insight",
            "insightful", "thanks", "thank", "sharing", "share", "post", "valuable", "value", "content", "amazing",
            "awesome", "love", "this", "that", "well", "said", "true", "spot", "totally", "agree", "absolutely",
            "important", "everyone", "understand", "inspiring", "powerful", "helpful", "informative", "perspective",
            "point", "points", "really", "such", "very", "much", "nice", "good", "brilliant", "excellent", "fantastic",
            "wonderful", "keep", "going", "work", "congrats", "congratulations", "indeed", "interesting", "article",
            "read", "always", "truly", "message", "words", "wise", "needed", "hear", "today", "make", "makes", "sense",
            "couldn't", "more", "with", "what", "your", "have", "been", "here", "there", "these", "those", "think");
    /** Life and career updates: congratulations, certifications, new jobs, promotions, anniversaries. */
    private static final Pattern MILESTONE = Pattern.compile("(\\b((happy|excited|thrilled|pleased|proud|delighted|honou?red|grateful)"
            + " to (share|announce)|starting a new (position|role|job|chapter)|new (position|role|job) (as|at)"
            + "|i'?ve (just )?(joined|accepted)|(joined|joining) [^.\\n]{0,40}\\bas (an? )?|work anniversary"
            + "|celebrating \\d+ years?|got promoted|promoted to|(earned|obtained|completed|received|achieved|passed|cleared)"
            + " (my |the |a |an )?[^.\\n]{0,50}(certification|certificate|certified|exam|degree|course)"
            + "|congratulations|congrats|graduated|new beginnings)\\b|#(certification|certified|newjob|newrole|newposition"
            + "|careergrowth|workanniversary|promotion|graduation)\\b)", Pattern.CASE_INSENSITIVE);
    /** Sales calls to action. */
    private static final Pattern PROMOTION = Pattern.compile("(\\b((book|schedule) (a|your) (free )?(call|demo|meeting|session)"
            + "|sign up|register (now|here|today)|enrol+(ment)? (now|today|open)|enrol+ (in|for)|link in (the )?(first )?comments?"
            + "|limited (seats|spots|slots)|use (the )?code|buy now|shop now|order now|pre-?order|get (yours|your copy|it now)"
            + "|free (webinar|trial|guide|e-?book|masterclass|workshop|template|checklist|audit)"
            + "|join (my|our) (newsletter|course|cohort|community|program|bootcamp|webinar)"
            + "|check out (my|our) (new )?(course|product|tool|app|service|book)|(dm|message) me (\"\\w+\"|to (get|learn|join)|for (details|the link))"
            + "|comment \"\\w+\" (below )?(and|&) i'?ll|vote for (us|our)|your vote|(we'?re|we are) (launching|live)"
            + "|launch(ed|ing) (our|my)|(our|my) (startup|company|product|platform|app|team) (is|goes|will|just)"
            + "|come (visit|see|meet) us|visit (us|our booth)|our booth|startup pitch|pitch (day|parade|competition)"
            + "|now available|available now|waitlist|early access|request a demo|free consultation)\\b|\\b\\d{1,2}% off\\b)", Pattern.CASE_INSENSITIVE);
    /** Phrasing AI models overuse. Several together, not one, point to AI-written text. */
    private static final Pattern AI_STYLE = Pattern.compile("(\\b(delve|game[- ]changer|in today'?s (fast[- ]paced|digital"
            + "|ever[- ]evolving|competitive)|ever[- ]evolving|unlock(ing)? (the|your)|let'?s dive in|here'?s the thing"
            + "|navigat(e|ing) the (complexities|landscape)|seamless(ly)?|tapestry|elevate your|harness(ing)? the power"
            + "|embark|in conclusion|key takeaways?|it'?s not just about|isn'?t just|the real magic|a testament to"
            + "|plays a (crucial|pivotal) role|crucial|pivotal|transformative|empower(ing)?|foster(ing)?|leverag(e|ing)"
            + "|supercharge|actionable insights?|thought leadership)\\b|—|[✅🚀💡👉🔑📌✨🔥⚡🎯])", Pattern.CASE_INSENSITIVE);
    private static final Pattern STEPS = Pattern.compile("(^\\s*(\\d+[.)]|step \\d+)|\\bhow to\\b|\\d+(\\.\\d+)?\\s?(%|ms|gb|mb|x\\b))",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern HASHTAG = Pattern.compile("(?<![\\w#])#[\\p{L}\\p{N}_]{2,}");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}']+");

    /**
     * @param texts      the account's posts or replies
     * @param parentText the post being replied to, or null for a feed (where there is no parent)
     */
    public TextSignals analyze(List<String> texts, String parentText) {
        String all = String.join("\n", texts);
        int words = count(WORD, all);
        int emojis = (int) all.codePoints().filter(TextSignalService::isEmoji).count();
        long letters = all.codePoints().filter(Character::isLetter).count();
        long upper = all.codePoints().filter(Character::isUpperCase).count();
        Double overlap = null;
        if (parentText != null) {
            var parent = words(parentText);
            overlap = texts.stream().mapToDouble(t -> overlap(words(t), parent)).max().orElse(0);
        }
        return new TextSignals(emojis, round(emojis / (double) Math.max(1, words)), matches(URGENCY, all),
                count(URL, all), matches(SHORTENER, all), matches(MESSAGING, all), matches(CRYPTO, all),
                count(MENTION, all), letters < 20 ? 0 : round(upper / (double) letters), THREAD_HOOK.matcher(all).find(),
                overlap == null ? null : round(overlap), matches(MONEY, all), matches(JOB, all), matches(ADULT, all),
                matches(FOLLOW, all), !texts.isEmpty() && texts.stream().allMatch(TextSignalService::isGenericPraise),
                matches(MILESTONE, all), matches(PROMOTION, all), matches(AI_STYLE, all), count(HASHTAG, all),
                words >= 60 && count(STEPS, all) >= 2);
    }

    /**
     * True when a short text praises without saying anything specific: it uses a praise phrase, and at most one of its
     * content words is outside the generic vocabulary. "Thanks for sharing, the partial-index tip saved our migration"
     * names specifics and is not generic.
     */
    static boolean isGenericPraise(String text) {
        if (!PRAISE.matcher(text).find()) return false;
        var m = WORD.matcher(text.replaceAll("@\\w+", " ").toLowerCase(Locale.ROOT));
        int words = 0, specific = 0;
        while (m.find()) {
            words++;
            if (m.group().length() > 3 && !GENERIC_WORDS.contains(m.group())) specific++;
        }
        return words <= 25 && specific <= 1;
    }

    static boolean isEmoji(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF) || cp == 0x2B50 || cp == 0x23F3;
    }

    private static List<String> matches(Pattern pattern, String text) {
        var found = new LinkedHashSet<String>();
        var m = pattern.matcher(text);
        while (m.find() && found.size() < 6) found.add(m.group().toLowerCase(Locale.ROOT));
        return List.copyOf(found);
    }

    private static int count(Pattern pattern, String text) {
        int n = 0;
        for (var m = pattern.matcher(text); m.find(); ) n++;
        return n;
    }

    private static HashSet<String> words(String text) {
        var set = new HashSet<String>();
        for (var m = WORD.matcher(text.toLowerCase(Locale.ROOT)); m.find(); ) if (m.group().length() > 3) set.add(m.group());
        return set;
    }

    /** Share of the reply's content words that also appear in the parent post. */
    private static double overlap(HashSet<String> reply, HashSet<String> parent) {
        if (reply.isEmpty()) return 0;
        return reply.stream().filter(parent::contains).count() / (double) reply.size();
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
