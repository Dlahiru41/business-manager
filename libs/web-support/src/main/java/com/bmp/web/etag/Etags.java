package com.bmp.web.etag;

import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Weak ETags carrying an aggregate's version: {@code W/"<version>"} (03-api-standards.md §4, §6). */
public final class Etags {

    private static final Pattern VERSION_TAG = Pattern.compile("^(?:W/)?\"(\\d{1,18})\"$");

    private Etags() {}

    public static String of(long version) {
        return "W/\"" + version + "\"";
    }

    /** The version in an ETag or If-Match value, if it is a single version tag. */
    public static OptionalLong parse(@Nullable String tag) {
        if (tag == null) {
            return OptionalLong.empty();
        }
        Matcher matcher = VERSION_TAG.matcher(tag.trim());
        return matcher.matches() ? OptionalLong.of(Long.parseLong(matcher.group(1))) : OptionalLong.empty();
    }
}
