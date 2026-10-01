package com.fashion.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Resolves a shopper's free-text query against the canonical taxonomy so that
 * phrases like "men wide leg denim", "women midi dress" or "denim jacket"
 * navigate to a real collection instead of returning the whole catalog.
 *
 * Resolution:
 *   1. detect a gender word ("women", "ladies", ...),
 *   2. match category / subcategory phrases from the taxonomy vocabulary,
 *      tolerating singular vs plural wording,
 *   3. rank every plausible reading of the query, most specific first, so the
 *      caller can take the first interpretation that actually has products.
 *
 * Step 3 matters because a single word can name more than one thing: "jacket"
 * is both the root category "Jackets &amp; Coats" and the subcategory "Jackets"
 * under "Denim". Rather than guessing, the candidate list is tried in order and
 * the first non-empty one wins.
 *
 * When no gender word is present both vocabularies are used and the gender is
 * left unresolved, so a bare "denim" spans mens and womens alike.
 */
@Service
public class TaxonomySearchService {

    private static final Map<String, String> GENDER_WORDS = Map.ofEntries(
            Map.entry("men", "MEN"), Map.entry("man", "MEN"), Map.entry("mens", "MEN"),
            Map.entry("male", "MEN"), Map.entry("boy", "MEN"), Map.entry("boys", "MEN"),
            Map.entry("women", "WOMEN"), Map.entry("woman", "WOMEN"), Map.entry("womens", "WOMEN"),
            Map.entry("lady", "WOMEN"), Map.entry("ladies", "WOMEN"),
            Map.entry("girl", "WOMEN"), Map.entry("girls", "WOMEN"));

    /** Words that carry no product meaning and should not become search tokens. */
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "the", "for", "with", "of", "in", "on", "to", "my", "me", "shop", "collection");

    private final JdbcTemplate db;

    public TaxonomySearchService(JdbcTemplate db) {
        this.db = db;
    }

    /** One reading of a query: an optional gender plus a collection to browse. */
    public record Combination(String gender, String mainCategory, String subcategory) {
    }

    /**
     * @param gender        "MEN", "WOMEN" or null when the query did not name one
     * @param combinations  plausible readings, most specific first
     * @param tokens        leftover words for free-text matching
     * @param rawQuery      the original query, for the plain-text fallback
     */
    public record Resolved(String gender, List<Combination> combinations, List<String> tokens, String rawQuery) {

        /** The reading to try first, or null when nothing pointed at the taxonomy. */
        public Combination best() {
            return combinations.isEmpty() ? null : combinations.get(0);
        }
    }

    /** A vocabulary entry: the words that trigger it, and what it means. */
    private record Phrase(List<String> words, String kind, String parentSlug, String slug) {
    }

    /** A subcategory hit, remembered together with the main category it sits under. */
    private record SubHit(String parentSlug, String slug, BitSet positions) {
    }

    /** A candidate reading plus how much of the query it accounts for. */
    private record Scored(Combination combination, int coverage, int order) {
    }

    public Resolved resolve(String raw) {
        String query = raw == null ? "" : raw.trim();
        if (query.isEmpty()) {
            return new Resolved(null, List.of(), List.of(), null);
        }

        String gender = null;
        String working = query;
        for (String word : working.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            String found = GENDER_WORDS.get(word);
            if (found != null) {
                gender = found;
                working = working.replaceAll("(?i)\\b" + java.util.regex.Pattern.quote(word) + "\\b", " ");
                break;
            }
        }

        String[] parts = working.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        List<String> queryWords = new ArrayList<>(parts.length);
        for (String word : parts) {
            if (!word.isEmpty()) {
                queryWords.add(word);
            }
        }

        // Longest phrases first, so "wide leg" beats a bare "leg".
        List<Phrase> vocabulary = vocabulary(gender);
        Map<String, BitSet> mains = new LinkedHashMap<>();
        List<SubHit> subs = new ArrayList<>();
        boolean[] consumed = new boolean[queryWords.size()];

        for (Phrase phrase : vocabulary) {
            int at = indexOf(queryWords, phrase.words());
            if (at < 0) {
                continue;
            }
            BitSet hit = new BitSet(queryWords.size());
            for (int i = at; i < at + phrase.words().size(); i++) {
                consumed[i] = true;
                hit.set(i);
            }
            if (phrase.kind().equals("cat")) {
                mains.merge(phrase.slug(), hit, TaxonomySearchService::or);
            } else {
                subs.add(new SubHit(phrase.parentSlug(), phrase.slug(), hit));
            }
        }

        List<String> leftover = new ArrayList<>();
        for (int i = 0; i < queryWords.size(); i++) {
            if (!consumed[i] && queryWords.get(i).length() >= 2 && !STOP_WORDS.contains(queryWords.get(i))
                    && !leftover.contains(queryWords.get(i))) {
                leftover.add(queryWords.get(i));
            }
        }

        return new Resolved(gender, rank(gender, mains, subs), leftover, query);
    }

    private static BitSet or(BitSet a, BitSet b) {
        BitSet merged = (BitSet) a.clone();
        merged.or(b);
        return merged;
    }

    /**
     * Builds the candidate readings, best explanation of the query first.
     *
     * A word can name several things, so every reading is scored by how many
     * query words it accounts for. "denim jacket" scores Denim &gt; Jackets as two
     * words (denim + jacket) against one word for Jackets &amp; Coats &gt; Utility
     * Jackets, which is why the denim reading is tried first. A subcategory is
     * only ever paired with the main category it actually belongs to, since
     * pairing it with an unrelated root would be meaningless.
     */
    private List<Combination> rank(String gender, Map<String, BitSet> mains, List<SubHit> subs) {
        List<Scored> scored = new ArrayList<>();
        int order = 0;
        for (SubHit sub : subs) {
            if (sub.parentSlug() != null) {
                BitSet union = or(mains.getOrDefault(sub.parentSlug(), new BitSet()), sub.positions());
                scored.add(new Scored(new Combination(gender, sub.parentSlug(), sub.slug()),
                        union.cardinality(), order++));
            }
        }
        for (Map.Entry<String, BitSet> main : mains.entrySet()) {
            scored.add(new Scored(new Combination(gender, main.getKey(), null),
                    main.getValue().cardinality(), order++));
        }
        for (SubHit sub : subs) {
            scored.add(new Scored(new Combination(gender, null, sub.slug()),
                    sub.positions().cardinality(), order++));
        }
        // Most of the query explained wins; a more specific reading breaks ties.
        scored.sort(Comparator.comparingInt(Scored::coverage).reversed()
                .thenComparing(Comparator.comparingInt((Scored s) ->
                        s.combination().subcategory() == null ? 0 : 1).reversed())
                .thenComparingInt(Scored::order));
        // A phrase can be reached by its name, its slug and its individual words,
        // so drop readings that resolve to the same collection.
        LinkedHashSet<Combination> unique = new LinkedHashSet<>();
        for (Scored candidate : scored) {
            unique.add(candidate.combination());
        }
        return List.copyOf(unique);
    }

    private static int indexOf(List<String> haystack, List<String> needle) {
        if (needle.isEmpty() || needle.size() > haystack.size()) {
            return -1;
        }
        outer:
        for (int i = 0; i + needle.size() <= haystack.size(); i++) {
            for (int j = 0; j < needle.size(); j++) {
                if (!sameWord(haystack.get(i + j), needle.get(j))) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Compares two words allowing for plural wording, so "dress"/"dresses",
     * "blouse"/"blouses" and "accessory"/"accessories" are the same word while
     * "shirt" and "s" are not. A suffix only counts as plural when it is exactly
     * s / es / ies, which keeps genuinely different words apart.
     */
    private static boolean sameWord(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        return isPluralSuffix(a, b) || isPluralSuffix(b, a);
    }

    /** True when {@code longer} is {@code shorter} plus nothing but a plural ending. */
    private static boolean isPluralSuffix(String longer, String shorter) {
        if (!longer.startsWith(shorter)) {
            return false;
        }
        String extra = longer.substring(shorter.length());
        return extra.equals("s") || extra.equals("es") || extra.equals("ies");
    }

    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!word.isEmpty()) {
                out.add(word);
            }
        }
        return out;
    }

    /**
     * Builds the phrase list. When {@code gender} is null both vocabularies are
     * loaded, so an unsexed query such as "denim" or "midi dress" still resolves.
     */
    private List<Phrase> vocabulary(String gender) {
        String sql = "SELECT name, slug, parent_id, "
                + " (SELECT p.slug FROM categories p WHERE p.id = c.parent_id) AS parent_slug "
                + "FROM categories c " + (gender == null ? "" : "WHERE c.gender = ? ")
                + "ORDER BY c.sort_order, c.id";
        List<Phrase> phrases = new ArrayList<>();
        List<Map<String, Object>> rows = db.queryForList(sql,
                gender == null ? new Object[]{} : new Object[]{gender});
        for (Map<String, Object> row : rows) {
            boolean root = row.get("parent_id") == null;
            String parent = root ? null : String.valueOf(row.get("parent_slug"));
            String kind = root ? "cat" : "sub";
            String slug = String.valueOf(row.get("slug"));
            List<String> name = words(String.valueOf(row.get("name")));
            phrases.add(new Phrase(name, kind, parent, slug));
            // Also match the human slug ("camp-collar" -> "camp collar").
            List<String> bySlug = words(slug.replace('-', ' '));
            if (!bySlug.equals(name)) {
                phrases.add(new Phrase(bySlug, kind, parent, slug));
            }
            // And each significant word on its own, so "bags" still finds
            // "Bags & Accessories". Single letters and "co" are dropped.
            for (String word : name) {
                if (word.length() >= 3 && !word.equals("and") && !name.equals(List.of(word))) {
                    phrases.add(new Phrase(List.of(word), kind, parent, slug));
                }
            }
        }
        phrases.sort(Comparator
                .comparingInt((Phrase p) -> p.words().size())
                .thenComparingInt(p -> p.kind().equals("cat") ? 0 : 1)
                .thenComparing(p -> String.join(" ", p.words())));
        return phrases;
    }
}
