# Xbox Audio Bridge

App Android que recebe só o áudio do Xbox pela rede (streaming local xHome) e toca no celular.

## Fluxo
1. Entrar com conta Xbox (login Microsoft no WebView, fluxo SISU).
2. Conectar: lista consoles, abre sessão, negocia WebRTC e toca o áudio recebido. O vídeo é ignorado.

O log na tela mostra cada etapa. "Copiar log" ajuda a diagnosticar problemas.

## APK
A cada push na `main`, o GitHub Actions compila e publica em Releases > latest.
