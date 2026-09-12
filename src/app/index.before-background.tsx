import React, { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Platform,
  SafeAreaView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

import * as Speech from 'expo-speech';

import {
  ExpoSpeechRecognitionModule,
  useSpeechRecognitionEvent,
} from 'expo-speech-recognition';

type VolumeLevel = '低' | '中' | '高';

export default function HomeScreen() {
  const [minutes, setMinutes] = useState<number | null>(null);
  const [secondsLeft, setSecondsLeft] = useState(0);
  const [isRunning, setIsRunning] = useState(false);
  const [isListening, setIsListening] = useState(false);
  const [isFinished, setIsFinished] = useState(false);
  const [volume, setVolume] = useState<VolumeLevel>('中');

  const endTimeRef = useRef<number | null>(null);
  const lastReminderRef = useRef<number | null>(null);
  const finishIntervalRef = useRef<ReturnType<typeof setInterval> | null>(
    null
  );

  // =========================
  // 語音辨識結果
  // =========================
  useSpeechRecognitionEvent('result', (event) => {
    const text = event.results?.[0]?.transcript ?? '';

    if (!text) {
      return;
    }

    setIsListening(false);

    // 支援「30 分鐘」、「30分鐘」、「30 min」等
    const match = text.match(
      /(\d+(?:\.\d+)?)\s*(分鐘|分|min|mins|minute|minutes)/i
    );

    if (!match) {
      Speech.speak('請說例如，倒數 10 分鐘，或倒數 30 分鐘。', {
        language: 'zh-TW',
        rate: 0.9,
      });
      return;
    }

    const value = Math.floor(Number(match[1]));

    if (value < 1) {
      Speech.speak('倒數時間至少要一分鐘。', {
        language: 'zh-TW',
        rate: 0.9,
      });
      return;
    }

    if (value > 180) {
      Speech.speak('時間不能超過一百八十分鐘。', {
        language: 'zh-TW',
        rate: 0.9,
      });
      return;
    }

    setMinutes(value);
    setSecondsLeft(value * 60);
    setIsRunning(false);
    setIsFinished(false);

    endTimeRef.current = null;
    lastReminderRef.current = value;

    Speech.speak(`好的，已設定倒數 ${value} 分鐘。`, {
      language: 'zh-TW',
      rate: 0.9,
    });
  });

  useSpeechRecognitionEvent('start', () => {
    setIsListening(true);
  });

  useSpeechRecognitionEvent('end', () => {
    setIsListening(false);
  });

  useSpeechRecognitionEvent('error', () => {
    setIsListening(false);
  });

  // =========================
  // 清除結束後重複提醒
  // =========================
  const stopFinishedSound = () => {
    if (finishIntervalRef.current) {
      clearInterval(finishIntervalRef.current);
      finishIntervalRef.current = null;
    }

    Speech.stop();
  };

  // =========================
  // 真正的倒數計時
  // 使用結束時間計算
  // =========================
  useEffect(() => {
    if (!isRunning) {
      return;
    }

    if (endTimeRef.current === null) {
      endTimeRef.current = Date.now() + secondsLeft * 1000;
    }

    const timer = setInterval(() => {
      const endTime = endTimeRef.current;

      if (!endTime) {
        return;
      }

      const remaining = Math.max(
        0,
        Math.ceil((endTime - Date.now()) / 1000)
      );

      setSecondsLeft(remaining);

      if (remaining <= 0) {
        clearInterval(timer);
        setIsRunning(false);
        setIsFinished(true);

        endTimeRef.current = null;

        Speech.speak('倒數時間到了。', {
          language: 'zh-TW',
          rate: 0.9,
        });

        // 持續提醒
        finishIntervalRef.current = setInterval(() => {
          Speech.speak('倒數時間到了，請按停止。', {
            language: 'zh-TW',
            rate: 0.9,
          });
        }, 5000);
      }
    }, 500);

    return () => {
      clearInterval(timer);
    };
  }, [isRunning]);

  // =========================
  // 每 5 分鐘提醒
  // =========================
  useEffect(() => {
    if (!isRunning || secondsLeft <= 0) {
      return;
    }

    const remainingMinutes = Math.ceil(secondsLeft / 60);

    if (
      remainingMinutes > 0 &&
      remainingMinutes % 5 === 0 &&
      lastReminderRef.current !== remainingMinutes
    ) {
      lastReminderRef.current = remainingMinutes;

      Speech.speak(`提醒，還剩 ${remainingMinutes} 分鐘。`, {
        language: 'zh-TW',
        rate: 0.9,
      });
    }
  }, [secondsLeft, isRunning]);

  // =========================
  // 語音輸入
  // =========================
  const startListening = async () => {
    if (isRunning || isFinished) {
      return;
    }

    if (Platform.OS !== 'android') {
      Alert.alert('提示', '目前以 Android 版本為主。');
      return;
    }

    try {
      const permission =
        await ExpoSpeechRecognitionModule.requestPermissionsAsync();

      if (!permission.granted) {
        Alert.alert(
          '需要麥克風權限',
          '請允許 VoiceTimer 使用麥克風。'
        );
        return;
      }

      ExpoSpeechRecognitionModule.start({
        lang: 'zh-TW',
        interimResults: false,
        continuous: false,
      });
    } catch {
      setIsListening(false);
    }
  };

  // =========================
  // 開始 / 停止
  // =========================
  const toggleTimer = () => {
    // 時間到了，只能停止提醒
    if (isFinished) {
      stopFinishedSound();
      setIsFinished(false);
      setSecondsLeft(0);
      setMinutes(null);
      lastReminderRef.current = null;
      return;
    }

    if (secondsLeft <= 0) {
      Speech.speak('請先設定倒數時間。', {
        language: 'zh-TW',
        rate: 0.9,
      });
      return;
    }

    if (isRunning) {
      // 暫停
      setIsRunning(false);
      endTimeRef.current = null;
      return;
    }

    // 開始
    endTimeRef.current = Date.now() + secondsLeft * 1000;
    setIsRunning(true);
  };

  // =========================
  // 點擊數字：報告剩餘時間
  // =========================
  const announceRemainingTime = () => {
    if (!isRunning || secondsLeft <= 0) {
      return;
    }

    const m = Math.floor(secondsLeft / 60);
    const s = secondsLeft % 60;

    if (m > 0 && s > 0) {
      Speech.speak(
        `目前剩餘 ${m} 分 ${s} 秒。`,
        {
          language: 'zh-TW',
          rate: 0.9,
        }
      );
    } else if (m > 0) {
      Speech.speak(
        `目前剩餘 ${m} 分鐘。`,
        {
          language: 'zh-TW',
          rate: 0.9,
        }
      );
    } else {
      Speech.speak(
        `目前剩餘 ${s} 秒。`,
        {
          language: 'zh-TW',
          rate: 0.9,
        }
      );
    }
  };

  // =========================
  // 顯示時間
  // =========================
  const formatTime = () => {
    const m = Math.floor(secondsLeft / 60);
    const s = secondsLeft % 60;

    return `${String(m).padStart(2, '0')}:${String(s).padStart(
      2,
      '0'
    )}`;
  };

  // =========================
  // 音量選擇
  // =========================
  const volumeOptions: VolumeLevel[] = ['低', '中', '高'];

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.content}>

        {/* 大型數字區域 */}
        <TouchableOpacity
          style={styles.timerArea}
          activeOpacity={0.8}
          onPress={announceRemainingTime}
        >
          <Text style={styles.timer}>
            {formatTime()}
          </Text>
        </TouchableOpacity>

        {/* 語音輸入 */}
        {!isRunning && !isFinished && (
          <>
            <TouchableOpacity
              style={styles.voiceButton}
              onPress={startListening}
              disabled={isListening}
            >
              <Text style={styles.voiceText}>
                {isListening ? '🎙️' : '🎙️'}
              </Text>
            </TouchableOpacity>

            {/* 音量 */}
            <View style={styles.volumeRow}>
              {volumeOptions.map((item) => (
                <TouchableOpacity
                  key={item}
                  style={[
                    styles.volumeButton,
                    volume === item && styles.volumeSelected,
                  ]}
                  onPress={() => setVolume(item)}
                >
                  <Text style={styles.volumeText}>
                    {item}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>
          </>
        )}

        {/* 開始 / 停止 */}
        <TouchableOpacity
          style={[
            styles.mainButton,
            isFinished && styles.stopButton,
          ]}
          onPress={toggleTimer}
        >
          <Text style={styles.mainButtonText}>
            {isFinished
              ? '停止'
              : isRunning
                ? '暫停'
                : '開始'}
          </Text>
        </TouchableOpacity>

      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000000',
  },

  content: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },

  timerArea: {
    width: '100%',
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },

  timer: {
    fontSize: 96,
    fontWeight: 'bold',
    color: '#ffffff',
    letterSpacing: 3,
  },

  voiceButton: {
    width: 80,
    height: 80,
    borderRadius: 40,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 12,
  },

  voiceText: {
    fontSize: 40,
  },

  volumeRow: {
    flexDirection: 'row',
    marginBottom: 12,
    gap: 8,
  },

  volumeButton: {
    paddingHorizontal: 18,
    paddingVertical: 8,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: '#777777',
  },

  volumeSelected: {
    backgroundColor: '#ffffff',
  },

  volumeText: {
    color: '#ffffff',
    fontSize: 16,
  },

  mainButton: {
    width: '85%',
    paddingVertical: 18,
    borderRadius: 18,
    backgroundColor: '#ffffff',
    alignItems: 'center',
    marginBottom: 25,
  },

  stopButton: {
    backgroundColor: '#ff3333',
  },

  mainButtonText: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#000000',
  },
});
