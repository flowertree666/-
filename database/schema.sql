CREATE DATABASE IF NOT EXISTS smart_travel DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE smart_travel;
CREATE TABLE IF NOT EXISTS attraction (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, city VARCHAR(50) NOT NULL,
 district VARCHAR(50), category VARCHAR(50), tags VARCHAR(255), summary VARCHAR(1000),
 longitude DOUBLE NOT NULL, latitude DOUBLE NOT NULL, duration_minutes INT NOT NULL,
 heat_score INT NOT NULL, crowd_index INT NOT NULL, crowd_trend VARCHAR(30), opening_hours VARCHAR(100),
 best_season VARCHAR(50), ticket_info VARCHAR(100), source_name VARCHAR(100), source_url VARCHAR(500),
 collected_at DATETIME, confidence DOUBLE, INDEX idx_city(city), INDEX idx_collected_at(collected_at)
);
