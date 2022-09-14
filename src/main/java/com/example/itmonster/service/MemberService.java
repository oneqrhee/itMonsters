package com.example.itmonster.service;

import com.example.itmonster.controller.request.SignupRequestDto;
import com.example.itmonster.controller.response.FollowResponseDto;
import com.example.itmonster.controller.response.MemberResponseDto;
import com.example.itmonster.controller.response.SocialLoginResponseDto;
import com.example.itmonster.controller.response.StackDto;
import com.example.itmonster.domain.Follow;
import com.example.itmonster.domain.Member;
import com.example.itmonster.domain.RoleEnum;
import com.example.itmonster.domain.StackOfMember;
import com.example.itmonster.exceptionHandler.CustomException;
import com.example.itmonster.exceptionHandler.ErrorCode;
import com.example.itmonster.repository.FollowRepository;
import com.example.itmonster.repository.MemberRepository;
import com.example.itmonster.repository.StackOfMemberRepository;
import com.example.itmonster.security.UserDetailsImpl;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class MemberService {

	private final MemberRepository memberRepository;
	private final StackOfMemberRepository stackOfMemberRepository;
	private final FollowRepository followRepository;
	private final PasswordEncoder passwordEncoder;
	private final AwsS3Service s3Service;

	String emailPattern = "^[0-9a-zA-Z]([-_.]?[0-9a-zA-Z])*@[0-9a-zA-Z]([-_.]?[0-9a-zA-Z])*.[a-zA-Z]{2,3}$"; //이메일 정규식 패턴
	String nicknamePattern = "^[a-zA-Z0-9ㄱ-ㅎ|ㅏ-ㅣ|가-힣~!@#$%^&*]{2,8}$"; // 영어대소문자 , 한글 , 특수문자포함 2~8자까지
	String passwordPattern = "^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{8,20}$"; //  영어대소문자,숫자 포함 8자에서 20자;
	String phoneNumPattern = "^(\\d{11})$"; // 11자리 숫자

//    @Value("${spring.admin.token}") // 어드민 가입용
//    String ADMIN_TOKEN;

	@Transactional
	public ResponseEntity signupUser(SignupRequestDto requestDto) throws IOException {

		String profileUrl = s3Service.getSavedS3ImageUrl(requestDto.getProfileImage());

		checkEmailPattern(requestDto.getEmail());//username 정규식 맞지 않는 경우 오류메시지 전달
		checkNicknamePatter(requestDto.getNickname());//nickname 정규식 맞지 않는 경우 오류메시지 전달
		checkPasswordPattern(requestDto.getPassword());//password 정규식 맞지 않는 경우 오류메시지 전달

		String password = passwordEncoder.encode(requestDto.getPassword()); // 패스워드 암호화

		Member member = Member.builder()
			.email(requestDto.getEmail())
			.nickname(requestDto.getNickname())
			.password(password)
			.profileImg(profileUrl)
			.phoneNum(null)
			.role(RoleEnum.USER)
			.followCounter(0L)
			.build();
		memberRepository.save(member);

		return new ResponseEntity("회원가입을 축하합니다", HttpStatus.OK);
	}


	@Transactional
	public ResponseEntity followMember(Long memberId, Member me) {
		Member member = memberRepository.findById(memberId).orElseThrow(
			() -> new CustomException(ErrorCode.USER_NOT_FOUND)); // 팔로우 할 멤버 확인

		if (followRepository.findByFollwingIdAndMeId(  // 팔로우 한 적 없으면 팔로우등록
			memberId, me.getId()) == null) {
			followRepository.save(Follow.builder()
				.me(me)
				.follwing(member)
				.build());
			member.addFollowCounter();
			memberRepository.save(member);
			return ResponseEntity.ok(FollowResponseDto.builder()
				.follow(true).build());

		} else { //팔로우 한적 있으면 취소

			Follow follow = followRepository.findByFollwingIdAndMeId(
				memberId, me.getId());
			followRepository.delete(follow);
			member.subFollowCounter();
			memberRepository.save(member);

			return ResponseEntity.ok(FollowResponseDto.builder().follow(false).build());
		}
	}

	@Transactional
	public ResponseEntity addStack(StackDto requestDto, Member member) { // 기술스택 추가
		if (stackOfMemberRepository.existsByMemberIdAndStackName(member.getId(),
			requestDto.getStackName())) {
			return ResponseEntity.ok(requestDto.getStackName() + "은(는) 이미 추가된 스택정보입니다.");
		}

		StackOfMember stack = StackOfMember.builder()
			.stackName(requestDto.getStackName())
			.member(member).build();
		stackOfMemberRepository.save(stack);
		return ResponseEntity.ok(requestDto.getStackName() + "을(를) 스택정보에 추가하였습니다.");
	}

	public List<StackDto> getStackList(Member member) {
		List<StackDto> stacks = new ArrayList<>();
		List<StackOfMember> stackOfMemberList = stackOfMemberRepository.findByMemberId(
			member.getId());
		if (stackOfMemberList.size() == 0L) {
			return stacks;
		}

		for (StackOfMember stack : stackOfMemberList) {
			stacks.add(new StackDto(stack.getStackName()));
		}

		return stacks;
	}

	public ResponseEntity showTop3Following() {
		List<Member> members = memberRepository.findTop3ByOrderByFollowCounter();
		List<MemberResponseDto> responseDtoList = new ArrayList<>();

		for (Member member : members) {
			responseDtoList.add(MemberResponseDto.builder()
				.nickname(member.getNickname())
				.profileImage(member.getProfileImg())
				.stacks(getStackList(member))
				.followCnt(member.getFollowCounter())
				.folioTitle(member.getNickname() + "님의 포트폴리오 제목") //임시
				.build());

		}
		return ResponseEntity.ok(responseDtoList);
	}


	//username 중복체크
	public ResponseEntity checkUsername(SignupRequestDto requestDto) {
		checkEmailPattern(requestDto.getEmail());
		return new ResponseEntity("사용 가능한 이메일입니다.", HttpStatus.OK);
	}

	public ResponseEntity checkNickname(SignupRequestDto requestDto) {
		checkNicknamePatter(requestDto.getNickname());
		return new ResponseEntity("사용 가능한 닉네임입니다.", HttpStatus.OK);
	}


	public MemberResponseDto memberInfo(Member member) {

		return MemberResponseDto.builder()
			.nickname(member.getNickname())
			.profileImage(member.getProfileImg())
			.stacks(getStackList(member))
			.followCnt(member.getFollowCounter())
			.folioTitle(member.getNickname() + "님의 포트폴리오 제목")
			.build();
	}


	//소셜로그인 사용자 정보 조회
	public ResponseEntity socialUserInfo(UserDetailsImpl userDetails) {
		//로그인 한 user 정보 검색
		Member member = memberRepository.findBySocialId(userDetails.getMember().getSocialId())
			.orElseThrow(
				() -> new CustomException(ErrorCode.USER_NOT_FOUND));

		//찾은 user엔티티를 dto로 변환해서 반환하기
		SocialLoginResponseDto socialLoginResponseDto = new SocialLoginResponseDto(member, true);
		return ResponseEntity.ok().body(socialLoginResponseDto);
	}


	public void checkEmailPattern(String email) {
		if (email == null) {
			throw new CustomException(ErrorCode.EMPTY_EMAIL);
		}
		if (email.equals("")) {
			throw new CustomException(ErrorCode.EMPTY_EMAIL);
		}
		if (!Pattern.matches(emailPattern, email)) {
			throw new CustomException(ErrorCode.EMAIL_WRONG);
		}
		if (memberRepository.findByEmail(email).isPresent()) {
			throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
		}
	}


	public void checkPasswordPattern(String password) {
		if (password == null) {
			throw new CustomException(ErrorCode.EMPTY_PASSWORD);
		}
		if (password.equals("")) {
			throw new CustomException(ErrorCode.EMPTY_PASSWORD);
		}
		if (8 > password.length() || 20 < password.length()) {
			throw new CustomException(ErrorCode.PASSWORD_LEGNTH);
		}
		if (!Pattern.matches(passwordPattern, password)) {
			throw new CustomException(ErrorCode.PASSWORD_WRONG);
		}
	}


	public void checkNicknamePatter(String nickname) {
		if (nickname == null) {
			throw new CustomException(ErrorCode.EMPTY_NICKNAME);
		}
		if (nickname.equals("")) {
			throw new CustomException(ErrorCode.EMPTY_NICKNAME);
		}
		if (2 > nickname.length() || 8 < nickname.length()) {
			throw new CustomException(ErrorCode.NICKNAME_LEGNTH);
		}
		if (!Pattern.matches(nicknamePattern, nickname)) {
			throw new CustomException(ErrorCode.NICKNAME_WRONG);
		}
	}

	public void checkPhoneNumb(String phoneNum) {
		if (phoneNum == null) {
			throw new CustomException(ErrorCode.EMPTY_PHONENUMBER);
		}
		if (phoneNum.equals("")) {
			throw new CustomException(ErrorCode.EMPTY_PHONENUMBER);
		}
		if (phoneNum.length() != 11) {
			throw new CustomException(ErrorCode.PHONENUMBER_LENGTH);
		}
		if (!Pattern.matches(phoneNumPattern, phoneNum)) {
			throw new CustomException(ErrorCode.PHONENUMBER_WRONG);
		}

	}


}

//로그인 후 관리자 권한 얻을 수 있는 API 관리자 접근 가능 페이지 없슴
//    public ResponseEntity adminAuthorization(AdminRequestDto requestDto, UserDetailsImpl userDetails) {
//        // 사용자 ROLE 확인
//        UserRoleEnum role = UserRoleEnum.USER;
//        if (requestDto.isAdmin()) {
//            if (!requestDto.getAdminToken().equals(ADMIN_TOKEN)) {
//                throw new CustomException(ErrorCode.INVALID_AUTHORITY_WRONG); // 토큰값이 틀림
//            }
//            role = UserRoleEnum.ADMIN;
//        }
//
//        //역할 변경
//        userDetails.getUser().setRole(role);
//        //변경된 역할 저장
//        userRepository.save(userDetails.getUser());
//        return new ResponseEntity("관리자 권한으로 변경되었습니다", HttpStatus.OK);
//    }




