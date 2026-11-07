package com.cretas.aims.service.execution;

import java.util.*;

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
    }

    static Options from(String input) {
        if(input==null || input.isBlank()) return empty();
        var numbers=LlmParameterGrounding.numberSpans(input);
        var candidates=new LinkedHashSet<String>();
        for(String clause:input.split("[，。；！？,;!?\\r\\n]+")) add(candidates,input,clause.trim(),numbers,-1,-1);
        add(candidates,input,input,numbers,0,input.length());
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
    private static void add(Set<String> out,String input,String text,List<LlmParameterGrounding.NumberSpan> numbers,int start,int end) {
        if(text.isBlank() || text.length()>200 || !unique(input,text)) return;
        if(start<0) { start=input.indexOf(text);end=start+text.length(); }
        for(var n:numbers) if(start>n.start() && start<n.end() || end>n.start() && end<n.end()) return;
        var own=LlmParameterGrounding.numberSpans(text);
        if(own.size()==1 && own.get(0).start()==0 && own.get(0).end()==text.length()) return;
        out.add(text);
    }
    private static Options empty() { return new Options(List.of(""),Set.of()); }
    private static boolean unique(String input,String evidence) {
        return input!=null && evidence!=null && !evidence.isBlank() && input.indexOf(evidence)>=0
                && input.indexOf(evidence)==input.lastIndexOf(evidence);
    }
}
