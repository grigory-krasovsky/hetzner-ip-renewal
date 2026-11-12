package com.example.hetzneriprenewal;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum Command {

    STATUS("status", "статус", "Статус"),
    ROTATE("rotate", "сменить", "Сменить IP"),
    PAUSE("pause", "пауза", "Пауза"),
    RESUME("resume", "продолжить", "Продолжить");

    private final String word;
    private final String russianWord;
    private final String label;

    Command(String word, String russianWord, String label) {
        this.word = word;
        this.russianWord = russianWord;
        this.label = label;
    }

    public static Optional<Command> parse(String text) {
        String normalized = text.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(command -> command.word.equals(normalized) || command.russianWord.equals(normalized))
                .findFirst();
    }

    public String word() {
        return word;
    }

    public String label() {
        return label;
    }

}
