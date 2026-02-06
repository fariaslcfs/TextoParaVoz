# TextoParaVoz

Um aplicativo Android simples que converte escrita à mão (ou texto digitado) em fala, usando reconhecimento de escrita digital do Google ML Kit e Text-to-Speech.

## Funcionalidades

- Reconhecimento de escrita à mão (modo manuscrito) → fala o texto reconhecido
- Modo teclado (entrada direta de texto) → fala o que foi digitado
- Suporte completo ao português brasileiro (voz e reconhecimento)
- Interface fullscreen imersiva
- Fallback automático para modo teclado em dispositivos antigos (Android 7.x)

## Requisitos

- Android 7.0+ (API 24)
- Conexão com internet na primeira execução (para baixar o modelo ~20–80 MB)
- Google Play Services atualizado

## Limitações conhecidas

- Em dispositivos **Android 7.x** (ex: LG Q6), o reconhecimento de escrita à mão pode não funcionar devido a limitações do Play Services antigo → o app usa automaticamente o modo teclado
- Em Android 8.0+ o reconhecimento funciona normalmente na maioria dos casos

## Como usar

1. Abra o aplicativo
2. Na primeira vez: aguarde o download do modelo (pode demorar alguns minutos em aparelhos mais antigos)
3. Escolha entre **Manuscrito** ou **Teclado**
4. Escreva ou digite o texto
5. Toque em **FALAR** para ouvir o resultado

## Tecnologias utilizadas

- Kotlin
- Google ML Kit – Digital Ink Recognition
- Android Text-to-Speech (TTS)
- AppCompat, Material Components
- minSdk 24, targetSdk 36

## Licença

[MIT License](LICENSE) – fique à vontade para usar, modificar e distribuir.

## Contato / Contribuições

Feedback, sugestões ou pull requests são bem-vindos!

Feito com ❤️ por Luiz e Grok