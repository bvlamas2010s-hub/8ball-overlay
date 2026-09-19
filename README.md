# 8ball-overlay

Android trajectory overlay prototype with automatic cloud APK builds.

## Baixar o APK pelo celular

1. Abra a aba **Actions** deste repositório.
2. Abra a execução mais recente chamada **Build Android APK**.
3. Aguarde o job ficar verde.
4. Na página da execução, role até **Artifacts**.
5. Baixe **trajectory-overlay-debug-apk**.
6. Extraia o ZIP baixado.
7. Instale **app-debug.apk** no Android.

## Se o Android bloquear a instalação

Permita temporariamente **Instalar apps desconhecidos** para o navegador ou gerenciador de arquivos usado para abrir o APK.

Depois de instalar, o app também pede:
- permissão para aparecer sobre outros apps;
- autorização do Android para captura/compartilhamento da tela;
- notificação do serviço em primeiro plano.

## Build

O GitHub Actions usa Java 17, Gradle 8.9 e Android SDK 35 para executar:

`gradle :app:assembleDebug`

O APK gerado fica em:

`app/build/outputs/apk/debug/app-debug.apk`
