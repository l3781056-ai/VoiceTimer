import React, { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Platform,
  SafeAreaView,
  StyleSheet,
  Text,
  TouchableOpacity,
  Vibration,
  View,
} from 'react-native';
import * as Speech from 'expo-speech';
import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake';
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
  const finishIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const finishTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 播放嗶嗶聲（高頻率嗶聲 + 震動）
  const playBeep = (count: number = 2) => {
    try {
      Vibration.vibrate([0, 150, 100, 150]);
      const volNum = volume === '低' ? 0.3 : volume === '中' ? 0.7 : 1.0;

      for (let i = 0; i < count; i++) {
        setTimeout(() => {
          Speech.speak('嗶', {
            language: 'zh-TW',
            pitch: 1.8,
            rate: 1.6,
            volume: volNum,
          });
        }, i * 280);
      }
    } catch (e) {
      console.warn('Beep error:', e);
    }
  };

  // 語音辨識事件
  useSpeechRecognitionEvent('result', (event) => {
    const text = event.results?.[0]?.transcript ?? '';
    if (!text) return;
    setIsListening(false);

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
      Speech.speak('倒數時間至少要一分鐘。', { language: 'zh-TW', rate: 0.9 });
      return;
    }
    if (value > 180) {
      Speech.speak('時間不能超過一百八十分鐘。', { language: 'zh-TW', rate: 0.9 });
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

  useSpeechRecognitionEvent('start', () => setIsListening(true));
  useSpeechRecognitionEvent('end', () => setIsListening(false));
  useSpeechRecognitionEvent('error', () => setIsListening(false));

  // 清除響鈴與解除防休眠
  const stopFinishedSound = () => {
    if (finishIntervalRef.current) {
      clearInterval(finishIntervalRef.current);
      finishIntervalRef.current = null;
    }
    if (finishTimeoutRef.current) {
      clearTimeout(finishTimeoutRef.current);
      finishTimeoutRef.current = null;
    }
    Speech.stop();
    Vibration.cancel();
    deactivateKeepAwake();
  };

  // 計時核心（基於時間戳校準）
  useEffect(() => {
    if (!isRunning) return;

    // 啟動計時時保持螢幕常亮防休眠
    activateKeepAwakeAsync();

    if (endTimeRef.current === null) {
      endTimeRef.current = Date.now() + secondsLeft * 1000;
    }

    const timer = setInterval(() => {
      const endTime = endTimeRef.current;
      if (!endTime) return;

      const remaining = Math.max(0, Math.ceil((endTime - Date.now()) / 1000));
      setSecondsLeft(remaining);

      // 時間歸零
      if (remaining <= 0) {
        clearInterval(timer);
        setIsRunning(false);
        setIsFinished(true);
        endTimeRef.current = null;

        playBeep(3);

        // 每 3 秒嗶一次，持續約 1 分鐘（60 秒後自動停止）
        finishIntervalRef.current = setInterval(() => {
          playBeep(2);
        }, 3000);

        finishTimeoutRef.current = setTimeout(() => {
          stopFinishedSound();
        }, 60000);
      }
    }, 500);

    return () => clearInterval(timer);
  }, [isRunning]);

  // 每 5 分鐘發出嗶嗶聲提醒
  useEffect(() => {
    if (!isRunning || secondsLeft <= 0) return;

    const remainingMinutes = Math.ceil(secondsLeft / 60);
    if (
      remainingMinutes > 0 &&
      remainingMinutes % 5 === 0 &&
      lastReminderRef.current !== remainingMinutes
    ) {
      lastReminderRef.current = remainingMinutes;
      playBeep(2);
    }
  }, [secondsLeft, isRunning]);

  const startListening = async () => {
    if (isRunning || isFinished) return;
    if (Platform.OS !== 'android') {
      Alert.alert('提示', '目前以 Android 版本為主。');
      return;
    }

    try {
      const permission =
        await ExpoSpeechRecognitionModule.requestPermissionsAsync();
      if (!permission.granted) {
        Alert.alert('需要麥克風權限', '請允許 VoiceTimer 使用麥克風。');
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

  const toggleTimer = () => {
    if (isFinished) {
      stopFinishedSound();
      setIsFinished(false);
      setSecondsLeft(0);
      setMinutes(null);
      lastReminderRef.current = null;
      return;
    }

    if (secondsLeft <= 0) {
      Speech.speak('請先設定倒數時間。', { language: 'zh-TW', rate: 0.9 });
      return;
    }

    if (isRunning) {
      setIsRunning(false);
      endTimeRef.current = null;
      deactivateKeepAwake();
      return;
    }

    endTimeRef.current = Date.now() + secondsLeft * 1000;
    setIsRunning(true);
  };

  const announceRemainingTime = () => {
    if (!isRunning || secondsLeft <= 0) return;
    const m = Math.floor(secondsLeft / 60);
    const s = secondsLeft % 60;
    if (m > 0 && s > 0) {
      Speech.speak(`目前剩餘 ${m} 分 ${s} 秒。`, { language: 'zh-TW', rate: 0.9 });
    } else if (m > 0) {
      Speech.speak(`目前剩餘 ${m} 分鐘。`, { language: 'zh-TW', rate: 0.9 });
    } else {
      Speech.speak(`目前剩餘 ${s} 秒。`, { language: 'zh-TW', rate: 0.9 });
    }
  };

  const formatTime = () => {
    const m = Math.floor(secondsLeft / 60);
    const s = secondsLeft % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  };

  const volumeOptions: VolumeLevel[] = ['低', '中', '高'];

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.content}>
        {/* 大型時間顯示 */}
        <TouchableOpacity
          style={styles.timerArea}
          activeOpacity={0.8}
          onPress={announceRemainingTime}
        >
          <Text style={styles.timer}>{formatTime()}</Text>
        </TouchableOpacity>

        {/* 語音按鈕：與開始鍵同等大小樣式 */}
        {!isRunning && !isFinished && (
          <>
            <TouchableOpacity
              style={[styles.mainButton, styles.voiceButton]}
              onPress={startListening}
              disabled={isListening}
            >
              <Text style={styles.voiceButtonText}>
                {isListening ? '🎙️ 正在聆聽中...' : '🎙️ 點擊語音設定時間'}
              </Text>
            </TouchableOpacity>

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
                  <Text
                    style={[
                      styles.volumeText,
                      volume === item && styles.volumeSelectedText,
                    ]}
                  >
                    {item}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>
          </>
        )}

        {/* 主動作鍵：開始 / 暫停 / 停止 */}
        <TouchableOpacity
          style={[styles.mainButton, isFinished && styles.stopButton]}
          onPress={toggleTimer}
        >
          <Text style={styles.mainButtonText}>
            {isFinished ? '停止' : isRunning ? '暫停' : '開始'}
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
    paddingBottom: 20,
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
  mainButton: {
    width: '85%',
    paddingVertical: 18,
    borderRadius: 18,
    backgroundColor: '#ffffff',
    alignItems: 'center',
    marginBottom: 16,
  },
  voiceButton: {
    backgroundColor: '#1f2937',
    borderWidth: 1.5,
    borderColor: '#3b82f6',
  },
  voiceButtonText: {
    fontSize: 20,
    fontWeight: 'bold',
    color: '#ffffff',
  },
  stopButton: {
    backgroundColor: '#ef4444',
  },
  mainButtonText: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#000000',
  },
  volumeRow: {
    flexDirection: 'row',
    marginBottom: 16,
    gap: 12,
  },
  volumeButton: {
    paddingHorizontal: 22,
    paddingVertical: 8,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: '#777777',
  },
  volumeSelected: {
    backgroundColor: '#ffffff',
    borderColor: '#ffffff',
  },
  volumeText: {
    color: '#ffffff',
    fontSize: 16,
  },
  volumeSelectedText: {
    color: '#000000',
    fontWeight: 'bold',
  },
});
