# WoodPrintCam

스마트폰 카메라로 촬영 → 미리보기 확인 → 캐논 셀피(SELPHY)로 바로 인쇄하는
안드로이드 네이티브 앱(Kotlin)입니다. 모던 우드톤 디자인이 적용되어 있습니다.

## 1. 프로젝트 열기
1. Android Studio (최신 버전 권장) 설치
2. `WoodPrintCam` 폴더를 "Open an existing project"로 열기
3. Gradle Sync가 끝날 때까지 대기 (최초 1회 인터넷 필요, 라이브러리 다운로드)

## 2. 실행
1. 실제 안드로이드 기기를 USB로 연결 (에뮬레이터는 카메라 기능 제한적)
2. Run ▶ 버튼으로 설치/실행
3. 최초 실행 시 카메라 권한 팝업이 뜨면 "허용" 선택
   → 이후에는 앱을 껐다 켜도 다시 묻지 않고 계속 사용 가능 (안드로이드 시스템이 자동 유지)

## 3. 캐논 셀피(SELPHY) 연결 방법
이 앱은 프린터와 직접 통신하지 않고, **안드로이드 표준 인쇄 프레임워크**를 사용합니다.
따라서 프린터 연결은 스마트폰의 시스템 설정에서 한 번만 해두면 됩니다.

- **Wi-Fi Direct로 연결하는 셀피 모델**: 셀피 본체에서 Wi-Fi Direct를 켜고,
  스마트폰의 Wi-Fi 목록에서 셀피의 네트워크(SSID)에 한 번 접속
- **Mopria 인증 모델**: Play스토어에서 "Mopria Print Service"를 설치하고
  설정 > 인쇄에서 활성화하면, 같은 네트워크의 셀피가 자동으로 검색됨
- 연결이 완료되면 앱의 "인쇄하기" 버튼을 눌렀을 때 뜨는 인쇄 다이얼로그의
  프린터 목록에 셀피가 표시됩니다.

## 4. 코드 구조
```
app/src/main/java/com/example/woodprintcam/MainActivity.kt   # 카메라, 촬영, 인쇄 전체 로직
app/src/main/res/layout/activity_main.xml                    # 화면 레이아웃
app/src/main/res/values/colors.xml                            # 우드톤 컬러 팔레트
app/src/main/res/drawable/                                    # 우드 그라데이션/버튼 배경
```

## 5. 주요 동작 흐름
1. 앱 실행 → 카메라 권한 확인 (없으면 최초 1회만 요청)
2. 카메라 프리뷰 표시 → 하단 셔터 버튼 촬영
3. 촬영 이미지 확인 화면 → "다시 찍기" 또는 "인쇄하기"
4. "인쇄하기" → 시스템 인쇄 다이얼로그 → 연결된 셀피 선택 → 인쇄

## 6. 커스터마이징 팁
- 색상: `res/values/colors.xml`에서 `wood_dark`, `wood_medium`, `wood_gold` 등 조정
- 인쇄 매수/용지 크기: 시스템 인쇄 다이얼로그에서 사용자가 직접 선택 (셀피 카드 크기 지원)
- 전면 카메라로 바꾸려면 `MainActivity.kt`의 `CameraSelector.DEFAULT_BACK_CAMERA`를
  `DEFAULT_FRONT_CAMERA`로 변경
