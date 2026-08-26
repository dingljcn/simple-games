package io.github.dingljcn.games.guessword.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class WordPoolService {

    private List<WordEntry> entries = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() throws IOException {
        ClassPathResource resource = new ClassPathResource("word-pool.json");
        try (InputStream is = resource.getInputStream()) {
            entries = objectMapper.readValue(is, new TypeReference<List<WordEntry>>() {});
        }
    }

    public List<String> pickWords(int count) {
        List<WordEntry> available = entries.stream()
                .filter(e -> e.getCnt() >= 0)
                .sorted(Comparator.comparingInt(WordEntry::getCnt))
                .collect(Collectors.toList());
        if (available.size() < count) {
            // 不够时重复使用，避免出错
            List<String> result = new ArrayList<>();
            for (WordEntry e : available) result.add(e.getWord());
            while (result.size() < count) result.add("备用词" + (result.size() + 1));
            return result.subList(0, count);
        }
        Collections.shuffle(available.subList(0, Math.min(available.size(), count * 2)));
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(available.get(i).getWord());
        }
        return result;
    }

    public void discardWords(List<String> words) throws IOException {
        for (String w : words) {
            for (WordEntry e : entries) {
                if (e.getWord().equals(w)) {
                    e.setCnt(-1);
                    break;
                }
            }
        }
        // 写回文件
        ClassPathResource resource = new ClassPathResource("word-pool.json");
        File file = resource.getFile();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, entries);
    }

    public static class WordEntry {
        private String word;
        private int cnt;
        public String getWord() { return word; }
        public void setWord(String word) { this.word = word; }
        public int getCnt() { return cnt; }
        public void setCnt(int cnt) { this.cnt = cnt; }
    }
}