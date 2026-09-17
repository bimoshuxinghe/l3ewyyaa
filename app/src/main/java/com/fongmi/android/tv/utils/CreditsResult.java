package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.bean.Person;

import java.util.ArrayList;
import java.util.List;

/**
 * TMDB Credits 结果，包含演员列表（cast）和主创列表（crew，含导演）
 */
public class CreditsResult {

    private final List<Person> cast;
    private final List<Person> crew;

    public CreditsResult() {
        this(new ArrayList<>(), new ArrayList<>());
    }

    public CreditsResult(List<Person> cast, List<Person> crew) {
        this.cast = cast == null ? new ArrayList<>() : cast;
        this.crew = crew == null ? new ArrayList<>() : crew;
    }

    public List<Person> getCast() {
        return cast;
    }

    public List<Person> getCrew() {
        return crew;
    }

    /**
     * 获取导演列表（从 crew 中筛选 department=Directing 或 job=Director）
     */
    public List<Person> getDirectors() {
        List<Person> directors = new ArrayList<>();
        for (Person p : crew) {
            if ("Directing".equals(p.getDepartment()) || "Director".equals(p.getJob())) {
                directors.add(p);
            }
        }
        return directors;
    }

    /**
     * 获取主要演员（前 N 个）
     */
    public List<Person> getTopCast(int limit) {
        if (cast.size() <= limit) return new ArrayList<>(cast);
        return new ArrayList<>(cast.subList(0, limit));
    }

    public boolean isEmpty() {
        return cast.isEmpty() && crew.isEmpty();
    }

    public static CreditsResult empty() {
        return new CreditsResult();
    }
}
