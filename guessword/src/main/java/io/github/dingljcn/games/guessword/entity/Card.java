package io.github.dingljcn.games.guessword.entity;

public class Card {
    private String word;
    private String color; // RED, BLUE, BLACK, CIVILIAN
    private boolean revealed;

    public Card(String word, String color, boolean revealed) {
        this.word = word;
        this.color = color;
        this.revealed = revealed;
    }

    public String getWord() { return word; }
    public void setWord(String word) { this.word = word; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public boolean isRevealed() { return revealed; }
    public void setRevealed(boolean revealed) { this.revealed = revealed; }
}
