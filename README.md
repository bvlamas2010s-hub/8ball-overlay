# Trajectory Overlay Lab v0.3

Protótipo Android para análise visual de geometria de sinuca em tempo real.

## Instalar

1. Abra **Actions** neste repositório.
2. Abra a execução verde mais recente de **Build Android APK**.
3. Em **Artifacts**, baixe **trajectory-overlay-v0.3-debug-apk**.
4. Extraia o ZIP e instale `app-debug.apk`.

A versão v0.3 usa uma chave de assinatura de desenvolvimento estável, então futuras builds podem ser instaladas por cima desta versão sem precisar desinstalar o app.

## Teste recomendado

Antes de abrir qualquer jogo/app:

1. Abra o Trajectory Overlay Lab.
2. Confirme que aparece **Overlay: OK • OpenCV: OK**.
3. Toque em **Testar overlay**.
4. Deve aparecer por 8 segundos:
   - um X azul no canto superior direito;
   - texto de diagnóstico no topo;
   - linhas de exemplo;
   - dois círculos de bola.

Se esse teste não aparecer, o problema está na permissão/renderização do overlay, não no detector.

## Análise da tela

1. Toque em **Iniciar análise**.
2. Autorize a captura/compartilhamento de tela.
3. Abra a tela que deseja analisar.
4. O topo do overlay informa:
   - se a mesa foi detectada automaticamente ou por fallback;
   - quantas bolas foram detectadas;
   - quantas rotas válidas foram calculadas.

O overlay também desenha:
- retângulo da mesa detectada;
- posições das caçapas;
- círculos das bolas detectadas;
- ponto fantasma de contato;
- trajetórias diretas;
- bank shots de uma tabela;
- trajetória residual estimada da branca.

## Diagnóstico rápido

- **X azul não aparece em lugar nenhum:** revisar permissão "Aparecer sobre outros apps".
- **X aparece em outros apps, mas some em um app específico:** o app alvo pode estar ocultando overlays do Android.
- **Mesa aparece, mas 0 ou 1 bola:** ajustar sensibilidade.
- **Bolas aparecem, mas 0 rotas:** detector está funcionando; nenhuma rota passou pelos filtros geométricos naquele frame.
- **Mensagem "Erro de análise":** envie o texto exato mostrado no topo/notificação.

## Qualidade automática

Toda build publicada executa:
- compilação do APK;
- testes unitários do motor geométrico;
- Android Lint;
- verificação do arquivo APK;
- assinatura estável de desenvolvimento.

O modelo de física é aproximado e não reproduz spin, atrito e dinâmica específica de um jogo com precisão perfeita.
