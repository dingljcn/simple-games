package io.github.dingljcn.games.guessword.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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

    /**
     * 优先选择 cnt 较小的词汇
     */
    public List<String> pickWords(int count) {
        List<WordEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(WordEntry::getCnt));
        int poolSize = Math.min(sorted.size(), Math.max(count, count * 3));
        List<WordEntry> pool = sorted.subList(0, poolSize);
        Collections.shuffle(pool);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count && i < pool.size(); i++) {
            result.add(pool.get(i).getWord());
        }
        return result;
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
