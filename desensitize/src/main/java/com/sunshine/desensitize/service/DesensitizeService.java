package com.sunshine.desensitize.service;

import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DesensitizeService {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)(\\d{17}[\\dXx])(?!\\d)");

    public String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        out = replaceAll(PHONE, out, m -> m.group(1).substring(0, 3) + "****" + m.group(1).substring(7));
        out = replaceAll(ID_CARD, out, m -> m.group(1).substring(0, 6) + "********" + m.group(1).substring(14));
        return out;
    }

    private static String replaceAll(Pattern pattern, String input, java.util.function.Function<Matcher, String> replacer) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(matcher)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
