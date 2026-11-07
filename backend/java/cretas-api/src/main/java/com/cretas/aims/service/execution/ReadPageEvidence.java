package com.cretas.aims.service.execution;

import java.util.*;
import java.util.regex.Pattern;

/** Bounded verbatim evidence choices. Does not determine intent, field or page action. */
final class ReadPageEvidence {
    private ReadPageEvidence() {}
    static boolean hasNumericContent(String input) {
        return input!=null && (!LlmParameterGrounding.numberMentions(input).isEmpty()
                || input.codePoints().anyMatch(ch->Character.isDigit(ch)
                    || Character.getType(ch)==Character.LETTER_NUMBER || Character.getType(ch)==Character.OTHER_NUMBER));
    }
    record Options(List<String> values, Set<String> allowed) {
        boolean allows(String input,String evidence) { return allowed.contains(evidence)&&unique(input,evidence); }
        boolean allowsRelative(String input,String evidence) {
            return allows(input,evidence) && !barePageQuantity(evidence);
        }
    }

    static Options from(String input) {
        if(input==null || input.isBlank()) return empty();
        var numbers=LlmParameterGrounding.numberSpans(input);
        var candidates=new LinkedHashSet<String>();
        var separators=Pattern.compile("[，。；！？,;!?\\r\\n]+").matcher(input);
        int clauseStart=0;
        while (separators.find()) {
            addClause(candidates,input,clauseStart,separators.start(),numbers);
            clauseStart=separators.end();
        }
        addClause(candidates,input,clauseStart,input.length(),numbers);
        add(candidates,input,input,numbers,0,input.length());
        // Keep a complete relative-action prefix when a clause also contains
        // another numeric field. This is a source fragment, not a direction
        // or field decision; add() still rejects prefixes with multiple numbers.
        separators.reset();
        clauseStart=0;
        while (separators.find()) {
            addPagePrefixes(candidates,input,clauseStart,separators.start(),numbers);
            clauseStart=separators.end();
        }
        addPagePrefixes(candidates,input,clauseStart,input.length(),numbers);
        // Add actual number + page-unit source fragments; this does not decide
        // the requested action. Whole clauses retain other field spellings.
        // No arbitrary character windows: those can split names or surrogates.
        for(var number:numbers) {
            int start=number.start(),end=number.end();
            while(start>0 && Character.isWhitespace(input.charAt(start-1))) start--;
            while(end<input.length() && Character.isWhitespace(input.charAt(end))) end++;
            if(start>0 && input.charAt(start-1)=='第') start--;
            if(end<input.length() && input.charAt(end)=='页')
                add(candidates,input,input.substring(start,end+1),numbers,start,end+1);
            if(candidates.size()>64) return empty();
        }
        if(candidates.size()>64) return empty();
        return candidates.isEmpty()?empty():new Options(List.copyOf(candidates),Set.copyOf(candidates));
    }
    private static void addClause(Set<String> out,String input,int rawStart,int rawEnd,
                                  List<LlmParameterGrounding.NumberSpan> numbers) {
        int start=rawStart,end=rawEnd;
        while(start<end && Character.isWhitespace(input.charAt(start))) start++;
        while(end>start && Character.isWhitespace(input.charAt(end-1))) end--;
        if(start<end) add(out,input,input.substring(start,end),numbers,start,end);
    }
    private static void addPagePrefixes(Set<String> out,String input,int rawStart,int rawEnd,
                                        List<LlmParameterGrounding.NumberSpan> numbers) {
        int start=rawStart,end=rawEnd;
        while(start<end && Character.isWhitespace(input.charAt(start))) start++;
        while(end>start && Character.isWhitespace(input.charAt(end-1))) end--;
        for(var number:numbers) {
            if(number.start()<start || number.end()>end) continue;
            int pageEnd=number.end();
            while(pageEnd<end && Character.isWhitespace(input.charAt(pageEnd))) pageEnd++;
            if(pageEnd<end && input.charAt(pageEnd)=='页')
                add(out,input,input.substring(start,pageEnd+1),numbers,start,pageEnd+1);
        }
    }
    private static void add(Set<String> out,String input,String text,List<LlmParameterGrounding.NumberSpan> numbers,int start,int end) {
        if(text.isBlank() || text.length()>200 || !unique(input,text)) return;
        if(start<0) { start=input.indexOf(text);end=start+text.length(); }
        for(var n:numbers) if(start>n.start() && start<n.end() || end>n.start() && end<n.end()) return;
        var own=LlmParameterGrounding.numberSpans(text);
        // A source choice with multiple numbers is ambiguous between page and
        // another numeric field (for example, page plus page size). Keep the
        // smaller page fragments/number-free actions instead; later semantic
        // validation still owns the meaning of any single-number choice.
        if (own.size() > 1) return;
        if(own.size()==1 && own.get(0).start()==0 && own.get(0).end()==text.length()) return;
        out.add(text);
    }
    private static Options empty() { return new Options(List.of(""),Set.of()); }
    private static boolean unique(String input,String evidence) {
        return input!=null && evidence!=null && !evidence.isBlank() && input.indexOf(evidence)>=0
                && input.indexOf(evidence)==input.lastIndexOf(evidence);
    }
    private static boolean barePageQuantity(String evidence) {
        if(evidence==null) return false;
        var numbers=LlmParameterGrounding.numberSpans(evidence);
        if(numbers.size()!=1) return false;
        var number=numbers.get(0);
        String before=evidence.substring(0,number.start()).trim();
        String after=evidence.substring(number.end()).trim();
        return (before.isEmpty() || before.equals("第")) && after.equals("页");
    }
}
