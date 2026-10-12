package com.leoneferito.home;

import org.springframework.data.jpa.repository.JpaRepository;

/** 메인 배너 설정 — 행은 하나(id = 1)뿐이다. */
public interface HeroConfigRepository extends JpaRepository<HeroConfig, Short> {
}
