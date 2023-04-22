package com.example.emotrak.Service;

import com.example.emotrak.dto.board.*;
import com.example.emotrak.dto.comment.CommentDetailResponseDto;
import com.example.emotrak.dto.like.LikeResponseDto;
import com.example.emotrak.dto.report.ReportRequestDto;
import com.example.emotrak.entity.*;
import com.example.emotrak.exception.CustomErrorCode;
import com.example.emotrak.exception.CustomException;
import com.example.emotrak.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.example.emotrak.entity.UserRoleEnum.ADMIN;

@Service
@RequiredArgsConstructor
@Transactional
public class BoardService {
    private final BoardRepository boardRepository;
    private final FileUploadService fileUploadService;
    private final EmotionRepository emotionRepository;
    private final CommentRepository commentRepository;
    private final ReportRepository reportRepository;
    private final LikesRepository likesRepository;

    @Value("#{'${app.image.allowedContentTypes}'.split(',')}")
    private List<String> allowedImageContentTypes;


    //감정글 추가
    public BoardIdResponseDto createDaily(BoardRequestDto boardRequestDto, User user, @Nullable MultipartFile image) {
        // 해당 날짜에 작성한 게시물 수 검색
        long dailyPosts = boardRepository.countDailyPostsByUserAndDate(user, boardRequestDto.getYear(), boardRequestDto.getMonth(), boardRequestDto.getDay());

        // 해당 날짜에 작성한 게시물이 2개 이상인지 확인
        if (dailyPosts >= 2) {
            throw new CustomException(CustomErrorCode.TOO_MANY_POSTS);
        }
        /*
         * 중복되는 이미지 처리 코드를 별도의 메서드로 분리
         * 업로드하려는 새 이미지 파일, 현재 이미지의 URL(createDaily 에서는 이미지가 없으므로 null 값을 전달)
         * 삭제할지 여부를 나타내는 boolean 값(요청에 이미지 삭제가 포함되어 있으면 true 값을 전달)
         */
        String imageUrl = handleImage(image, null, false);
        // Emotion 객체 찾기
        Emotion emotion = findEmotionById(boardRequestDto.getEmoId());
        // Daily 객체 생성 및 저장
        Daily daily = new Daily(imageUrl, boardRequestDto, user, emotion);
        boardRepository.save(daily);

        return new BoardIdResponseDto(daily);
    }

    // 글 수정
    public void updateDaily(Long dailyId, BoardRequestDto boardRequestDto, User user, MultipartFile image) {
        Daily daily = findDailyById(dailyId);
        if (daily.isHasRestrict() && daily.isShare()){
            throw new CustomException(CustomErrorCode.RESTRICT_ERROR);
        }
        validateUserOrAdmin(user, daily);
        String newImageUrl = handleImage(image, daily.getImgUrl(), boardRequestDto.isDeleteImg());
        // Emotion 객체 찾기
        Emotion emotion = findEmotionById(boardRequestDto.getEmoId());
        // Daily 객체 업데이트 및 저장
        daily.update(newImageUrl, boardRequestDto, emotion);
    }

    // 글 삭제
    public void deleteDaily(Long dailyId, User user) {
        Daily daily = findDailyById(dailyId);
        validateUserOnly(user, daily);
        // 이미지가 null이 아닌 경우에만 S3에서 이미지 파일 삭제
        if (daily.getImgUrl() != null) {
            fileUploadService.deleteFile(daily.getImgUrl());
        }

        // 댓글 좋아요 날리기
        likesRepository.deleteCommentLike(daily.getId());

        // 댓글 신고 날리기
        reportRepository.deleteCommentByDaily(daily.getId());

        // 댓글 날리기
        commentRepository.deleteByDaily(daily.getId());

        // 게시글 좋아요 날리기
        likesRepository.deleteBoardLike(daily.getId());

        // 게시글 신고 날리기
        reportRepository.deleteAllByDaily(daily);

        // 데이터베이스에서 Daily 객체 삭제
        boardRepository.delete(daily);
    }

    //예외처리
    public void validateImage(MultipartFile image) {
        if (!allowedImageContentTypes.contains(image.getContentType())) {
            throw new CustomException(CustomErrorCode.INVALID_FILE_TYPE);
        }
    }

    private Daily findDailyById(Long dailyId) {
        return boardRepository.findById(dailyId)
                .orElseThrow(() -> new CustomException(CustomErrorCode.BOARD_NOT_FOUND));
    }

    private Emotion findEmotionById(Long emotionId) {
        return emotionRepository.findById(emotionId)
                .orElseThrow(() -> new CustomException(CustomErrorCode.CONTENT_NOT_FOUND));
    }

    private String handleImage(MultipartFile image, String currentImageUrl, boolean deleteImg) {
        String newImageUrl = currentImageUrl;
        //deleteImg 가 true(이미지삭제요청)인 경우 기존 이미지 삭제
        if (deleteImg) {
            if (currentImageUrl != null) {
                fileUploadService.deleteFile(currentImageUrl);
            }
            newImageUrl = null;
        }
        // 이미지가 null 이 아니고 비어있지 않은 경우, 새로운 이미지 업로드
        if (image != null && !image.isEmpty()) {
            validateImage(image);
            newImageUrl = fileUploadService.uploadFile(image);
        }
        return newImageUrl;
    }

    // 수정 권한을 해당 유저와 관리자만 가능하게 메소드
    private void validateUserOrAdmin(User user, Daily daily) {
        if (daily.getUser().getId() != user.getId() && user.getRole() != ADMIN) {
            throw new CustomException(CustomErrorCode.NOT_AUTHOR);
        }
    }

    // 삭제 권한을 해당 유저만 가능하게 변경
    private void validateUserOnly(User user, Daily daily) {
        if (daily.getUser().getId() != user.getId()) {
            throw new CustomException(CustomErrorCode.NOT_AUTHOR);
        }
    }


    // 공유게시판 전체조회(이미지)
    @Transactional(readOnly = true)
    public BoardImgPageRequestDto getBoardImages(int page, int size, String emo, String sort) {
        Stream<String> stringStream = Arrays.stream(emo.split(","));
        List<Long> emoList = stringStream.parallel().mapToLong(Long::parseLong).boxed().collect(Collectors.toList());

        Pageable pageable = PageRequest.of(page-1, size+1);

        List<Object[]> objectList;
        if (sort.equals("recent")) objectList = boardRepository.getBoardImagesRecent(emoList, pageable);
        else objectList = boardRepository.getBoardImagesPopular(emoList, pageable);

        boolean lastPage = true;
        List<BoardImgRequestDto> boardImgRequestDtoList = new ArrayList<>();
        for (int i = 0; i < objectList.size(); i++){
            if (i == size) {
                lastPage = false;
                break;
            }
            BoardImgRequestDto boardImgRequestDto = new BoardImgRequestDto(objectList.get(i));
            boardImgRequestDtoList.add(boardImgRequestDto);
        }

        return new BoardImgPageRequestDto(lastPage, boardImgRequestDtoList);
    }

    // 공유게시판 상세페이지
    @Transactional(readOnly = true)
    public BoardDetailResponseDto getBoardDetail(Long id, User user, int page) {
        Daily daily = findDailyById(id);
        // share 가 false 이고, 사용자와 작성자가 다를 경우 예외 처리
        if (!daily.isShare() && (user == null || daily.getUser().getId() != user.getId())) {
            throw new CustomException(CustomErrorCode.UNAUTHORIZED_ACCESS);
        }
        // 사용자와 게시물 간의 좋아요 관계 확인
        boolean hasLike = likesRepository.findByUserAndDaily(user, daily).isPresent();
        // 사용자가 게시물을 신고했는지 확인
        boolean hasReport = user != null ? reportRepository.findByUserAndDailyId(user, id).isPresent() : false;
        // 게시글의 전체 댓글 수 계산
        int totalComments = commentRepository.countByDaily(daily);
        // 페이지네이션을 적용하여 댓글 목록 가져오기
        Pageable pageable = PageRequest.of(page-1, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Comment> commentsPage = commentRepository.findAllByDaily(daily, pageable);
        boolean lastPage = commentsPage.isLast();
        List<CommentDetailResponseDto> commentDetailResponseDtoList = commentsPage.getContent().stream()
                .map(comment -> { //쿼리날아가는거 한번 수정 필요 ! 무조건,,, 네이티브 쿼리일듯..? 아무튼 상세페이지는 리펙토링이 필요하다
                    // 사용자와 댓글 간의 좋아요 관계 확인 및 설정
                    boolean commentHasLike = user != null ? likesRepository.findByUserAndComment(user, comment).isPresent() : false;
                    // 사용자가 댓글을 신고했는지 확인
                    boolean commentHasReport = user != null ? reportRepository.findByUserAndCommentId(user, comment.getId()).isPresent() : false;
                    return new CommentDetailResponseDto(comment, user, likesRepository.countByComment(comment), commentHasLike, commentHasReport);
                })
                .collect(Collectors.toList());
        return new BoardDetailResponseDto(daily, user, commentDetailResponseDtoList, likesRepository.countByDaily(daily), hasLike, lastPage, hasReport, totalComments);
    }


    //게시물 신고하기
    public void createReport(ReportRequestDto reportRequestDto, User user, Long id) {
        Daily daily = findDailyById(id);
        Optional<Report> existingReport = reportRepository.findByUserAndDailyId(user, id);
        if (existingReport.isPresent()) {
            throw new CustomException(CustomErrorCode.DUPLICATE_REPORT);
        }
        Report report = new Report(reportRequestDto, user, daily);
        reportRepository.save(report);
    }

    //게시글 좋아요 (좋아요와 취소 번갈아가며 진행)
    public LikeResponseDto boardLikes(User user, Long boardId) {
        Daily daily = findDailyById(boardId);
        Optional<Likes> likes = likesRepository.findByUserAndDaily(user, daily);
        boolean like = likes.isEmpty();

        if (like) {
            // 좋아요 추가
            likesRepository.save(new Likes(daily, user));
        } else {
            // 이미 좋아요한 경우, 좋아요 취소
            likesRepository.deleteById(likes.get().getId());
        }

        return new LikeResponseDto(like, likesRepository.countByDaily(daily));
    }

}
