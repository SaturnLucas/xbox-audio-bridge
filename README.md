# Xbox Audio Bridge

App Android para receber só o áudio do Xbox Series S pela rede Wi-Fi, com o menor atraso possível.

## Estado
- Pronto: interface, saída de áudio de baixa latência, tom de teste, build automático do APK.
- Falta: `NanoAudioSource.kt` (login Xbox, sessão Nano, áudio Opus). Referência: projeto XStreaming.

## APK
A cada push na branch `main`, o GitHub Actions compila e publica o APK em Releases > latest.
