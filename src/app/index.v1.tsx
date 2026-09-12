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

export default function HomeScreen() {
  const [minutes, setMinutes] = useState<number | null>(null);
  const [secondsLeft, setSecondsLeft] = useState(0);
  const [isRunning, setIsRunning] = useState(false);
  const [isListening, setIsListening] = useState(false);
  const [status, setStatus] = useState('請按「語音輸入時間」');

  const lastReminderRef = useRef<number | null>(null);

  // =========================
  // 語音辨識結果
  // =========================
  useSpeechRecognitionEvent('result', (event) => {
    const text = event.results?.[0]?.transcript ?? '';

    if (!text) {
      return;
    }

    setIsListening(false);

    const match = text.match(
      /(\d+(?:\.\d+)?)\s*(分鐘|分|min|mins|minute|minutes)/i
    );

    if (!match) {
      setStatus(`聽到：「${text}」，沒有找到分鐘數`);

      Speech.speak(
        '請說例如，倒數十分鐘，或倒數三十分鐘。',
        {
          language: 'zh-TW',
          rate: 0.9,
        }
      );

      return;
    }

    const value = Math.floor(Number(match[1]));

    // 小於 1 分鐘
    if (value < 1) {
      setStatus('時間至少 1 分鐘');

      Speech.speak(
        '倒數時間至少要一分鐘。',
        {
          language: 'zh-TW',
          rate: 0.9,
        }
      );

      return;
    }

    // 超過 180 分鐘
    if (value > 180) {
      setStatus('超過最大 180 分鐘');

      Speech.speak(
        '時間不能超過一百八十分鐘。',
        {
          language: 'zh-TW',
          rate: 0.9,
        }
      );

      return;
    }

    // 設定倒數時間
    setMinutes(value);
    setSecondsLeft(value * 60);
    setIsRunning(false);

    // 避免剛設定時立即觸發提醒
    lastReminderRef.current = value;

    setStatus(`已設定 ${value} 分鐘`);

    Speech.speak(
      `好的，已設定倒數 ${value} 分鐘。`,
      {
        language: 'zh-TW',
        rate: 0.9,
      }
    );
  });

  // =========================
  // 開始聆聽
  // =========================
  useSpeechRecognitionEvent('start', () => {
    setIsListening(true);
    setStatus('正在聽，請說倒數幾分鐘');
  });

  // =========================
  // 語音辨識結束
  // =========================
  useSpeechRecognitionEvent('end', () => {
    setIsListening(false);
  });

  // =========================
  // 語音辨識錯誤
  // =========================
  useSpeechRecognitionEvent('error', (event) => {
    setIsListening(false);

    setStatus(
      `語音辨識失敗：${event.error ?? '未知錯誤'}`
    );
  });

  // =========================
  // 倒數計時
  // =========================
  useEffect(() => {
    if (!isRunning || secondsLeft <= 0) {
      return;
    }

    const timer = setInterval(() => {
      setSecondsLeft((current) => {
        if (current <= 1) {
          clearInterval(timer);

          setIsRunning(false);

          Speech.speak(
            '倒數時間到了。',
            {
              language: 'zh-TW',
              rate: 0.9,
            }
          );

          setStatus('時間到了！');

          return 0;
        }

        return current - 1;
      });
    }, 1000);

    return () => {
      clearInterval(timer);
    };
  }, [isRunning, secondsLeft]);

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

      Speech.speak(
        `提醒，還剩 ${remainingMinutes} 分鐘。`,
        {
          language: 'zh-TW',
          rate: 0.9,
        }
      );

      setStatus(
        `提醒：還剩 ${remainingMinutes} 分鐘`
      );
    }
  }, [secondsLeft, isRunning]);

  // =========================
  // 啟動語音辨識
  // =========================
  const startListening = async () => {
    if (Platform.OS !== 'android') {
      Alert.alert(
        '提示',
        '目前這個試用版先以 Android 為主。'
      );

      return;
    }

    try {
      const permission =
        await ExpoSpeechRecognitionModule.requestPermissionsAsync();

      if (!permission.granted) {
        Alert.alert(
          '需要麥克風權限',
          '請允許 VoiceTimer 使用麥克風，才能使用語音輸入。'
        );

        return;
      }

      setStatus('正在啟動語音辨識...');

      ExpoSpeechRecognitionModule.start({
        lang: 'zh-TW',
        interimResults: false,
        continuous: false,
      });
    } catch (error) {
      console.log(error);

      setIsListening(false);
      setStatus('無法啟動語音辨識');
    }
  };

  // =========================
  // 開始 / 暫停
  // =========================
  const toggleTimer = () => {
    if (secondsLeft <= 0) {
      Speech.speak(
        '請先設定倒數時間。',
        {
          language: 'zh-TW',
          rate: 0.9,
        }
      );

      setStatus('請先設定倒數時間');

      return;
    }

    setIsRunning((value) => !value);
  };

  // =========================
  // 重設
  // =========================
  const resetTimer = () => {
    setIsRunning(false);

    if (minutes !== null) {
      setSecondsLeft(minutes * 60);

      lastReminderRef.current = minutes;

      setStatus(`已重設為 ${minutes} 分鐘`);
    } else {
      setSecondsLeft(0);

      setStatus('請先輸入倒數時間');
    }
  };

  // =========================
  // 顯示時間
  // =========================
  const formatTime = () => {
    const m = Math.floor(secondsLeft / 60);
    const s = secondsLeft % 60;

    return `${String(m).padStart(3, '0')}:${String(s).padStart(2, '0')}`;
  };

  // =========================
  // 畫面
  // =========================
  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.content}>

        <Text style={styles.title}>
          語音倒數計時器
        </Text>

        <Text style={styles.subtitle}>
          最大 180 分鐘・每 5 分鐘提醒
        </Text>

        <View style={styles.timerBox}>
          <Text style={styles.timer}>
            {formatTime()}
          </Text>
        </View>

        <Text style={styles.status}>
          {status}
        </Text>

        <TouchableOpacity
          style={[
            styles.voiceButton,
            isListening && styles.listeningButton,
          ]}
          onPress={startListening}
          disabled={isListening}
        >
          <Text style={styles.voiceButtonText}>
            {isListening
              ? '🎙️ 正在聽...'
              : '🎙️ 語音輸入時間'}
          </Text>
        </TouchableOpacity>

        <Text style={styles.example}>
          例如說：「倒數 30 分鐘」
        </Text>

        <View style={styles.row}>

          <TouchableOpacity
            style={styles.controlButton}
            onPress={toggleTimer}
          >
            <Text style={styles.controlText}>
              {isRunning ? '⏸ 暫停' : '▶ 開始'}
            </Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.controlButton}
            onPress={resetTimer}
          >
            <Text style={styles.controlText}>
              ↻ 重設
            </Text>
          </TouchableOpacity>

        </View>

      </View>
    </SafeAreaView>
  );
}

// =========================
// 樣式
// =========================
const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },

  content: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 24,
  },

  title: {
    fontSize: 30,
    fontWeight: 'bold',
    marginBottom: 10,
  },

  subtitle: {
    fontSize: 17,
    marginBottom: 30,
  },

  timerBox: {
    width: '100%',
    paddingVertical: 25,
    borderRadius: 20,
    backgroundColor: '#ffffff',
    alignItems: 'center',
    marginBottom: 20,
  },

  timer: {
    fontSize: 58,
    fontWeight: 'bold',
    letterSpacing: 2,
  },

  status: {
    fontSize: 18,
    textAlign: 'center',
    marginBottom: 20,
    minHeight: 28,
  },

  voiceButton: {
    width: '100%',
    paddingVertical: 18,
    borderRadius: 15,
    backgroundColor: '#208AEF',
    alignItems: 'center',
    marginBottom: 12,
  },

  listeningButton: {
    opacity: 0.6,
  },

  voiceButtonText: {
    color: '#ffffff',
    fontSize: 20,
    fontWeight: 'bold',
  },

  example: {
    fontSize: 16,
    marginBottom: 25,
  },

  row: {
    width: '100%',
    flexDirection: 'row',
    gap: 12,
  },

  controlButton: {
    flex: 1,
    paddingVertical: 16,
    borderRadius: 15,
    backgroundColor: '#333333',
    alignItems: 'center',
  },

  controlText: {
    color: '#ffffff',
    fontSize: 19,
    fontWeight: 'bold',
  },
});
