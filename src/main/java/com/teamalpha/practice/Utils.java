package com.teamalpha.practice;


//longestPalindromicSubstring implemented with Manacher's algorithm (O(n)).
public class Utils {

    public static String longestPalindromicSubstring(String s) {
        if (s == null || s.isEmpty()) return "";

        // Transform s to t with separators to handle even-length palindromes uniformly.
        // Example: s = "abba" -> t = "^#a#b#b#a#$"
        int n = s.length();
        char[] t = new char[2 * n + 3];
        t[0] = '^';
        int idx = 1;
        t[idx++] = '#';
        for (int i = 0; i < n; i++) {
            t[idx++] = s.charAt(i);
            t[idx++] = '#';
        }
        t[idx++] = '$';
        // now t length = idx

        int[] p = new int[idx];
        int center = 0, right = 0;
        for (int i = 1; i < idx - 1; i++) {
            int mirror = 2 * center - i;
            if (i < right) p[i] = Math.min(right - i, p[mirror]);
            // expand around i
            while (t[i + 1 + p[i]] == t[i - 1 - p[i]]) p[i]++;
            if (i + p[i] > right) {
                center = i;
                right = i + p[i];
            }
        }

        // find max
        int maxLen = 0;
        int centerIndex = 0;
        for (int i = 1; i < idx - 1; i++) {
            if (p[i] > maxLen) {
                maxLen = p[i];
                centerIndex = i;
            }
        }
        // start index in original string
        int start = (centerIndex - maxLen - 1) / 2;
        return s.substring(start, start + maxLen);
    }
}

