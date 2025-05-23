package com.example.AOD.movie.CGV.crawler;

import com.example.AOD.movie.CGV.dto.MovieDTO;
import com.example.AOD.util.ChromeDriverProvider;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@Slf4j
public class CgvCrawler {

    private final String BASE_URL = "http://www.cgv.co.kr";
    private final String MOVIE_LIST_URL = BASE_URL + "/movies/";
    private final String MOVIE_DETAIL_URL = BASE_URL + "/movies/detail-view/?midx=";

    private static final int CONNECTION_TIMEOUT = 30000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";
    private static final int WAIT_TIMEOUT = 10;
    private static final int MAX_CLICK_ATTEMPTS = 10;

    private final ChromeDriverProvider chromeDriverProvider;

    public CgvCrawler(ChromeDriverProvider chromeDriverProvider) {
        this.chromeDriverProvider = chromeDriverProvider;
    }

    public List<MovieDTO> crawlAll() {
        log.info("CGV 전체 영화 크롤링 시작");
        List<MovieDTO> crawledMovies = new ArrayList<>();
        WebDriver driver = null;

        try {
            // WebDriver 초기화
            driver = chromeDriverProvider.getDriver();
            driver.get(MOVIE_LIST_URL);

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(WAIT_TIMEOUT));
            clickMoreButtonUntilNoMore(driver, wait);

            log.info("영화 정보 추출 중...");
            List<String> externalIds = extractAllMovieIds(driver);
            log.info("총 {}개 영화 발견", externalIds.size());

            for (String externalId : externalIds) {
                try {
                    // 영화 상세 정보를 DTO로 가져옴
                    MovieDTO movieDTO = crawlMovieDetail(externalId);
                    if (movieDTO != null) {
                        crawledMovies.add(movieDTO);
                        log.info("영화 크롤링 완료: {}", movieDTO.getTitle());
                    }

                    delay();
                } catch (Exception e) {
                    log.error("영화 ID {} 상세 정보 크롤링 오류: {}", externalId, e.getMessage(), e);
                }
            }

            log.info("CGV 전체 영화 크롤링 완료. {}개 영화 크롤링됨", crawledMovies.size());
        } catch (Exception e) {
            log.error("Selenium 크롤링 오류: {}", e.getMessage(), e);
        } finally {
            if (driver != null) {
                driver.quit();
                log.info("WebDriver 종료");
            }
        }

        return crawledMovies;
    }

    private void clickMoreButtonUntilNoMore(WebDriver driver, WebDriverWait wait) {
        int clickCount = 0;
        boolean hasMoreButton = true;

        while (hasMoreButton && clickCount < MAX_CLICK_ATTEMPTS) {
            try {
                Thread.sleep(1000);

                WebElement moreButton = null;
                try {
                    moreButton = wait.until(
                            ExpectedConditions.elementToBeClickable(
                                    By.cssSelector(".btn-more-fontbold")
                            )
                    );
                } catch (Exception e) {
                    log.info("'더보기' 버튼이 더 이상 없습니다. 모든 영화 로드됨.");
                    hasMoreButton = false;
                    break;
                }

                if (!moreButton.isDisplayed() || !moreButton.isEnabled()) {
                    log.info("'더보기' 버튼이 보이지 않거나 활성화되지 않았습니다. 모든 영화 로드됨.");
                    hasMoreButton = false;
                    break;
                }

                ((JavascriptExecutor) driver).executeScript(
                        "arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});",
                        moreButton
                );

                Thread.sleep(500);
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", moreButton);

                log.info("'더보기' 버튼 클릭. 시도: {}", clickCount + 1);
                clickCount++;
                Thread.sleep(1500);

            } catch (Exception e) {
                log.warn("'더보기' 버튼 클릭 오류: {}", e.getMessage());
                clickCount++;
            }
        }

        if (clickCount >= MAX_CLICK_ATTEMPTS) {
            log.info("최대 클릭 시도 횟수({})에 도달. 로드된 영화로 진행.", MAX_CLICK_ATTEMPTS);
        }
    }

    private List<String> extractAllMovieIds(WebDriver driver) {
        List<String> externalIds = new ArrayList<>();
        List<WebElement> movieElements = driver.findElements(By.cssSelector(".sect-movie-chart ol li"));

        for (WebElement movieElement : movieElements) {
            try {
                WebElement linkElement = movieElement.findElement(By.cssSelector(".box-image a"));
                String href = linkElement.getAttribute("href");
                String externalId = extractExternalId(href);

                if (!externalId.isEmpty()) {
                    externalIds.add(externalId);
                }
            } catch (Exception e) {
                log.warn("영화 ID 추출 오류: {}", e.getMessage());
            }
        }

        return externalIds;
    }

    private MovieDTO crawlMovieDetail(String externalId) {
        try {
            String url = MOVIE_DETAIL_URL + externalId;
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(CONNECTION_TIMEOUT)
                    .get();

            String title = doc.select(".box-contents .title strong").text();
            if (title.isEmpty()) {
                log.warn("영화 제목을 찾을 수 없습니다. externalId: {}", externalId);
                return null;
            }

            String director = doc.select(".spec dl dt:contains(감독) + dd a").text();

            Elements actorElements = doc.select(".spec dl dt:contains(배우) + dd.on a");
            List<String> actors = new ArrayList<>();
            for (Element actorElement : actorElements) {
                String actorName = actorElement.text().trim();
                if (!actorName.isEmpty()) {
                    actors.add(actorName);
                }
            }

            List<String> genres = parseGenres(doc);

            String ratingText = doc.select(".egg-gage.small .percent").text();
            Double rating = parseRating(ratingText);

            String reservationRateText = doc.select(".score .percent span").text();
            Double reservationRate = parseReservationRate(reservationRateText);

            String basicInfoText = doc.select(".spec dl dt:contains(기본 정보) + dd.on").text();
            String ageRating = parseAgeRating(basicInfoText);
            Integer runningTime = parseRunningTime(basicInfoText);
            String country = parseCountry(basicInfoText);

            String releaseDateText = doc.select(".spec dl dt:contains(개봉) + dd").text();
            LocalDate releaseDate = parseReleaseDate(releaseDateText);
            boolean isRerelease = isRerelease(releaseDateText);

            // 썸네일 이미지 URL 추출
            String thumbnailUrl = parseThumbnailUrl(doc);

            // DTO 생성
            return MovieDTO.builder()
                    .title(title)
                    .director(director)
                    .actors(actors)
                    .genres(genres)
                    .rating(rating)
                    .reservationRate(reservationRate)
                    .runningTime(runningTime)
                    .country(country)
                    .releaseDate(releaseDate)
                    .isRerelease(isRerelease)
                    .ageRating(ageRating)
                    .thumbnailUrl(thumbnailUrl) // 썸네일 URL 추가
                    .externalId(externalId)
                    .lastUpdated(LocalDate.now())
                    .build();

        } catch (IOException e) {
            log.error("ID {}의 영화 상세 정보 크롤링 오류: {}", externalId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 영화 포스터 썸네일 URL을 추출합니다.
     */
    private String parseThumbnailUrl(Document doc) {
        try {
            // 메인 포스터 이미지 선택자
            Element posterElement = doc.selectFirst(".box-image a .thumb-image img");

            if (posterElement != null) {
                String thumbnailUrl = posterElement.attr("src");

                // URL이 없거나 비어있는 경우 대체 방법 시도
                if (thumbnailUrl == null || thumbnailUrl.isEmpty()) {
                    // 대체 이미지 선택자 시도
                    Element altPosterElement = doc.selectFirst(".sect-movie-chart .box-image img");
                    if (altPosterElement != null) {
                        thumbnailUrl = altPosterElement.attr("src");
                    }
                }

                // 상대 경로인 경우 절대 경로로 변환
                if (thumbnailUrl != null && !thumbnailUrl.isEmpty() && !thumbnailUrl.startsWith("http")) {
                    thumbnailUrl = "http://www.cgv.co.kr" + thumbnailUrl;
                }

                return thumbnailUrl;
            }

            log.warn("영화 썸네일 이미지를 찾을 수 없습니다.");
            return "";

        } catch (Exception e) {
            log.error("썸네일 URL 파싱 오류: {}", e.getMessage());
            return "";
        }
    }

    private List<String> parseGenres(Document doc) {
        List<String> genres = new ArrayList<>();

        // 장르 정보가 들어있는 dt 요소 찾기
        Element genreElement = doc.selectFirst(".spec dl dt:contains(장르 :)");

        if (genreElement != null) {
            String genreText = genreElement.text().trim();

            // "장르 :" 부분 제거
            if (genreText.startsWith("장르 :")) {
                genreText = genreText.substring("장르 :".length()).trim();

                // 콤마로 구분된 장르들 분리
                genres = Arrays.stream(genreText.split(","))
                        .map(s -> s.trim())
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());

            }
        } else {
            log.warn("장르 정보를 찾을 수 없습니다");
        }

        return genres;
    }

    private Double parseRating(String ratingText) {
        try {
            return Double.parseDouble(ratingText.replace("%", "").trim()) / 10.0;
        } catch (NumberFormatException e) {
            log.warn("평점 파싱 오류: {}", ratingText);
            return 0.0;
        }
    }

    private Double parseReservationRate(String reservationRateText) {
        try {
            return Double.parseDouble(reservationRateText.replace("%", "").trim());
        } catch (NumberFormatException e) {
            log.warn("예매율 파싱 오류: {}", reservationRateText);
            return 0.0;
        }
    }

    private String parseAgeRating(String basicInfoText) {
        try {
            if (basicInfoText == null || basicInfoText.isEmpty()) {
                return "";
            }

            // 첫 번째 콤마까지의 텍스트가 관람 연령대
            int commaIndex = basicInfoText.indexOf(",");
            if (commaIndex > 0) {
                return basicInfoText.substring(0, commaIndex).trim();
            }

            // 콤마가 없으면 전체 텍스트 반환
            return basicInfoText.trim();
        } catch (Exception e) {
            log.warn("관람 연령대 파싱 오류: {}", basicInfoText);
            return "";
        }
    }

    private Integer parseRunningTime(String basicInfoText) {
        try {
            if (basicInfoText.contains("분")) {
                // 분을 포함하는 부분 추출
                String[] parts = basicInfoText.split(",");
                for (String part : parts) {
                    part = part.trim();
                    if (part.contains("분")) {
                        return Integer.parseInt(part.replaceAll("[^0-9]", ""));
                    }
                }
            }
            return 0;
        } catch (NumberFormatException e) {
            log.warn("상영시간 파싱 오류: {}", basicInfoText);
            return 0;
        }
    }

    private String parseCountry(String basicInfoText) {
        try {
            if (basicInfoText == null || basicInfoText.isEmpty()) {
                return "-";
            }

            // 콤마로 분리
            String[] parts = basicInfoText.split(",");

            // 마지막 부분이 있는지 확인하고, 그 값이 비어있는지 확인
            if (parts.length > 0) {
                String lastPart = parts[parts.length - 1].trim();

                // 마지막 부분이 비어있거나 오직 공백만 포함하고 있는 경우
                if (lastPart.isEmpty() || lastPart.matches("^\\s*$")) {
                    return "-";
                }

                // 마지막 부분이 숫자와 "분"만을 포함하는 경우 (상영시간인 경우)
                if (lastPart.matches("^\\s*\\d+\\s*분\\s*$")) {
                    return "-";
                }

                // 그 외의 경우 마지막 부분을 국가 정보로 간주
                return lastPart;
            }

            // 콤마가 없거나 다른 예외 경우
            return "-";

        } catch (Exception e) {
            log.warn("국가 정보 파싱 오류: {}", basicInfoText);
            return "-";
        }
    }

    private LocalDate parseReleaseDate(String releaseDateText) {
        try {
            // "(재개봉)" 등의 부가 정보를 제거하고 날짜만 추출
            String dateOnly = releaseDateText.replaceAll("\\(.*\\)", "").trim();

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd");
            return LocalDate.parse(dateOnly, formatter);
        } catch (Exception e) {
            log.warn("개봉일 파싱 오류: {}", releaseDateText);
            return LocalDate.now();
        }
    }

    private boolean isRerelease(String releaseDateText) {
        boolean result = releaseDateText.contains("재개봉");
        return result;
    }

    private String extractExternalId(String href) {
        if (href.contains("midx=")) {
            return href.substring(href.indexOf("midx=") + 5);
        }
        return "";
    }

    private void delay() {
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}