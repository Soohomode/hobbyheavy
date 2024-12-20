package com.example.hobbyheavy.service;

import com.example.hobbyheavy.dto.request.UserJoinRequest;
import com.example.hobbyheavy.dto.request.UserPwUpdateRequest;
import com.example.hobbyheavy.dto.request.UserUpdateRequest;
import com.example.hobbyheavy.dto.response.UserInfoResponse;
import com.example.hobbyheavy.entity.Hobby;
import com.example.hobbyheavy.entity.User;
import com.example.hobbyheavy.exception.CustomException;
import com.example.hobbyheavy.exception.ExceptionCode;
import com.example.hobbyheavy.repository.HobbyRepository;
import com.example.hobbyheavy.repository.UserRepository;
import com.example.hobbyheavy.type.UserRole;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final HobbyRepository hobbyRepository;
    private final BCryptPasswordEncoder bCryptPasswordEncoder;

    /** 회원가입 유효성 체크 메서드 **/
    void checkJoin(UserJoinRequest userJoinRequest) {

        // UserId 중복 체크 (탈퇴하지 않은 활성화된 아이디)
        if (userRepository.existsByUserIdAndDeletedFalse(userJoinRequest.getUserId())) {
            throw new CustomException(ExceptionCode.USER_ID_ALREADY_IN_USE);
        }

        // Email 중복 체크 (탈퇴하지 않은 활성화된 이메일)
        if (userRepository.existsByEmailAndDeletedFalse(userJoinRequest.getEmail())) {
            throw new CustomException(ExceptionCode.EMAIL_ALREADY_IN_USE);
        }

    }

    /** 사용자 조회 공통 메서드 **/
    private User getUser(String userId) {
        return userRepository.findByUserIdAndDeletedFalse(userId)
                .orElseThrow(() -> {
                    log.warn("사용자 조회 실패. 입력한 사용자 ID: {}", userId);
                    return new CustomException(ExceptionCode.USER_NOT_FOUND);
                });
    }

    /** 비밀번호 확인 공통 메서드 **/
    private void checkPassword(String userId, String password, String storedPassword) {
        if (!bCryptPasswordEncoder.matches(password, storedPassword)) {
            log.warn("비밀번호 불일치. 사용자 ID: {}", userId);
            throw new CustomException(ExceptionCode.PASSWORD_MISMATCH);
        }
    }

    /** 취미 ID 가져오기 공통 메서드 **/
    private Hobby getHobbyById(Long hobbyId) {
        return hobbyRepository.findById(hobbyId)
                .orElseThrow(() -> new CustomException(ExceptionCode.HOBBY_NOT_FOUND));
    }

    /** ===비즈니스 로직=== **/

    /** 회원 가입 메서드 **/
    public void JoinUser(UserJoinRequest userJoinRequest) {

        // 탈퇴된 계정 DB 에서 삭제
        userRepository.findByUserIdAndDeletedTrue(userJoinRequest.getUserId())
                .ifPresent(user -> {
                    userRepository.delete(user);
                    log.info("탈퇴 상태의 기존 사용자(ID: {})를 삭제했습니다.", user.getUserId());
                });

        userRepository.findByEmailAndDeletedTrue(userJoinRequest.getEmail())
                .ifPresent(user -> {
                    userRepository.delete(user);
                    log.info("탈퇴 상태의 기존 이메일({})을 가진 사용자를 삭제했습니다.", user.getEmail());
                });

        // 유효성 체크
        checkJoin(userJoinRequest);

        // 취미 처리: hobbyIds를 기반으로 Hobby 엔터티 조회
        Set<Hobby> hobbies = new HashSet<>();
        if (userJoinRequest.getHobbyIds() != null && !userJoinRequest.getHobbyIds().isEmpty()) {
            for (Long hobbyId : userJoinRequest.getHobbyIds()) {
                Hobby hobby = getHobbyById(hobbyId);
                hobbies.add(hobby);
            }
        }

        // 사용자 저장
        userRepository.save(User.builder()
                .userId(userJoinRequest.getUserId())
                .username(userJoinRequest.getUsername())
                .password(bCryptPasswordEncoder.encode(userJoinRequest.getPassword())) // 암호화된 비밀번호
                .email(userJoinRequest.getEmail())
                .gender(userJoinRequest.getGender())
                .age(userJoinRequest.getAge())
                .hobbies(hobbies)
                .alarm(true)
                .userRole(Collections.singleton(UserRole.ROLE_USER)) // 역할이 존재할 때 설정
                .build());
        log.info("새 사용자 가입 완료. 사용자 ID: {}", userJoinRequest.getUserId());
    }

    /** 나의 회원정보 조회 메서드 **/
    public UserInfoResponse getMyUserInfo(String userId) {

        // 사용자 조회
        User user = getUser(userId);
        log.info("사용자 조회 성공. 사용자 ID: {}", userId);

        // 조회된 사용자 정보로 UserInfoDTO 생성 후 리턴
        return new UserInfoResponse().toUserInfoDTO(user);
    }

    /** 나의 회원정보 변경 메서드 **/
    @Transactional
    public void updateUserInfo(String userId, UserUpdateRequest request) {

        // 사용자 조회
        User user = getUser(userId);
        log.info("유저 정보 찾음. userId: {}, 현재 username: {}, 현재 hobbies: {}", userId, user.getUsername(), user.getHobbies());
        log.info("hobbyIds: {}", request.getHobbyIds());  // hobbyIds가 null인지, 비어있는지 확인하기 위해 추가

        // 기존 취미 목록을 Set으로 관리
        Set<Hobby> updatedHobbies = new HashSet<>();

        if (request.getHobbyIds() != null && !request.getHobbyIds().isEmpty()) {
            for (Long hobbyId : request.getHobbyIds()) {
                Hobby hobby = getHobbyById(hobbyId);
                updatedHobbies.add(hobby);
                log.info("취미 변경됨. hobbyId: {}", hobbyId);
            }
        }

        // 새로운 취미 목록으로 덮어쓰기
        user.setUsername(request.getUsername());  // 이름 업데이트
        user.setGender(request.getGender());
        user.setAge(request.getAge());
        user.setAlarm(request.getAlarm());
        user.setHobbies(updatedHobbies);  // 취미 업데이트

        userRepository.save(user);
        log.info("유저 정보 업데이트 완료. userId: {}", userId);
    }

    /** 비밀번호 변경 메서드 **/
    @Transactional
    public void updatePassword(String userId, UserPwUpdateRequest request) {

        // 사용자 조회
        User user = getUser(userId);

        // 기존 비밀번호 확인
        checkPassword(userId, request.getOldPassword(), user.getPassword());

        // 기존 비밀번호와 새 비밀번호가 같을 경우 예외 처리
        if (bCryptPasswordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            log.warn("새 비밀번호가 기존 비밀번호와 동일합니다. 사용자 ID: {}", userId);
            throw new CustomException(ExceptionCode.PASSWORD_SAME_AS_OLD);
        }

        // 새 비밀번호로 변경
        user.updatePassword(bCryptPasswordEncoder.encode(request.getNewPassword()));

        userRepository.save(user);
        log.info("사용자 ID: {}의 비밀번호가 성공적으로 변경되었습니다.", userId);
    }

    /** 회원 탈퇴 메서드 **/
    @Transactional
    public void deleteUser(String userId, String password) {
        // 사용자 조회
        User user = getUser(userId);

        // 비밀번호 확인
        checkPassword(userId, password, user.getPassword());

        // 이미 논리적으로 탈퇴된 사용자 여부 확인
        if (user.isDeleted()) {
            log.warn("사용자 ID: {}는 이미 탈퇴한 상태입니다.", userId);
            throw new CustomException(ExceptionCode.USER_DELETED); // 예외 처리
        }

        // 논리적 삭제 처리
        user.markAsDeleted(); // deleted 값을 true 로 설정하고 deleted_at 시간을 기록

        // 사용자 업데이트 (DB에 반영)
        userRepository.save(user);
        log.info("사용자 ID: {}의 계정이 논리적으로 삭제되었습니다.", userId);
    }

}
