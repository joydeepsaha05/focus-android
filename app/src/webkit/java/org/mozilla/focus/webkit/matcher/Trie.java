/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.webkit.matcher;

import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.SparseArray;

import org.mozilla.focus.webkit.matcher.util.FocusString;

/* package-private */ class Trie {

    /**
     * Trie that adds storage for a whitelist (itself another trie) on each node.
     */
    public static class WhiteListTrie extends Trie {
        Trie whitelist = null;

        private WhiteListTrie(final char character, final WhiteListTrie parent) {
            super(character, parent);
        }

        @Override
        protected Trie createNode(final char character, final Trie parent) {
            return new WhiteListTrie(character, (WhiteListTrie) parent);
        }

        public static WhiteListTrie createRootNode() {
            return new WhiteListTrie(Character.MIN_VALUE, null);
        }

        /* Convenience method so that clients aren't forced to do their own casting. */
        public void putWhiteList(final FocusString string, final Trie whitelist) {
            WhiteListTrie node = (WhiteListTrie) super.put(string);

            if (node.whitelist != null) {
                throw new IllegalStateException("Whitelist already set for node " + string);
            }

            node.whitelist = whitelist;
        }
    }

    public final SparseArray<Trie> children = new SparseArray<>();
    public boolean terminator = false;

    public Trie findNode(final FocusString string) {
        if (terminator) {
            // Match achieved - and we're at a domain boundary. This is important, because
            // we don't want to return on partial domain matches. (E.g. if the trie node is bar.com,
            // and the search string is foo-bar.com, we shouldn't match. But foo.bar.com should match.)
            if (string.length() == 0 || string.charAt(0) == '.') {
                return this;
            }
        } else if (string.length() == 0) {
            // Finished the string, no match
            return null;
        }

        final Trie next = children.get(string.charAt(0));

        if (next == null) {
            return null;
        }

        return next.findNode(string.substring(1));
    }

    public @Nullable Trie findNode(final String input) {
        return findNode(input, this);
    }

    /**
     * Search for the node representing a specific domain.
     *
     * This is an iterative implementation, that is specific to domain searches (i.e. we assume
     * that the domain is stored in reverse format in the Trie, we therefore walk backwards along the
     * "input"/search string, and have special conditions for subdomains.
     *
     * Recursive non-static implementations are possible, but less efficient - moreover handling
     * the input String gets messy (we can substring which is inefficient, we can pass around string
     * offsets which gets messy, or we can have a String wrapper class which is convoluted). Earlier
     * implementations did in fact use substring() (which was slowest since Android substring creates
     * a copy), followed by a String wrapper taking care of offsets (which was significantly faster,
     * but still convoluted and hard to understand).
     *
     * Search performance is particularly important as this method is called ~3 times per webpage resource
     * (once per enabled blocklist). Webpages typically try to load tens of resources. Naive search
     * implementations can therefore impact perceived page load performance.
     *
     * See the following for more background on the evolution of our trie search:
     *
     */
    private static @Nullable Trie findNode(final String input, final Trie rootNode) {
        // Note: if two nodes can validly represent a given domain, the shorter one is returned.
        // E.g. if the trie contains both "bar.com" and "foo.bar.com", then findNode("foo.bar.com") or
        // even findNode("*.foo.bar.com") will return the node for bar.com.
        // TODO: we could try to detect the above ^ when elements are being inserted into the Trie,
        // or alternatively we could store the first node that is found as a "candidate" while
        // continuing to search the trie for more specific entries.
        // To detect insertion of subdomains, we need to verify whether the parent node is a "terminator"
        // whenever a domain separator (i.e. '.') is encountered during node insertion.

        if (TextUtils.isEmpty(input)) {
            return null;
        }

        int offset = 0;
        Trie node = rootNode;

        while (offset < input.length()) {
            if (node == null) {
                return null;
            }

            final int currentCharPosition = input.length() - 1 - offset;
            final char currentChar = input.charAt(currentCharPosition);

            // Match achieved - and we're at a domain boundary. This is important, because
            // we don't want to return on partial domain matches. (E.g. if the trie node is bar.com,
            // and the search string is foo-bar.com, we shouldn't match. foo.bar.com should however match.)
            if (node.terminator && currentChar == '.') {
                return node;
            }

            node = node.children.get(currentChar);
            offset++;
        }

        if (node.terminator) {
            // This only happens in the case 1:1 matches - i.e. we finished walking the input string and found
            // a terminator node exactly matching the input string. Partial matches (subdomains) are handled above.
            return node;
        } else {
            return null;
        }
    }

    public Trie put(final FocusString string) {
        if (string.length() == 0) {
            terminator = true;
            return this;
        }

        final char character = string.charAt(0);

        final Trie child = put(character);

        return child.put(string.substring(1));
    }

    public Trie put(char character) {
        final Trie existingChild = children.get(character);

        if (existingChild != null) {
            return existingChild;
        }

        final Trie newChild = createNode(character, this);

        children.put(character, newChild);

        return newChild;
    }

    private Trie(char character, Trie parent) {
        if (parent != null) {
            parent.children.put(character, this);
        }
    }

    public static Trie createRootNode() {
        return new Trie(Character.MIN_VALUE, null);
    }

    // Subclasses must override to provide their node implementation
    protected Trie createNode(final char character, final Trie parent) {
        return new Trie(character, parent);
    }
}
