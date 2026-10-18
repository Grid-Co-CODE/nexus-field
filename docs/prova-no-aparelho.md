# Prova no aparelho

O roteiro da seção 10.3 da spec (`docs/superpowers/specs/2026-10-16-app-campo-android-design.md`), em caixas de seleção,
mais o que as correções de 17/10/2026 pedem para ver num Android de verdade. Marque cada caixa só depois de ver
acontecer; o que falhar vira uma nota embaixo do item (o que se fez, o que se viu, a hora), sem nome de pessoa.

## Antes de começar

- Usuário de teste combinado com o PCM: `________________` (o responsável pela prova preenche; nunca um técnico real)
- OS de teste combinada com o PCM: `________________` (nenhuma OS real é usada)
- Aparelho: `________________` (modelo e versão do Android)
- APK: `________________` (depuração ou Release, e o commit ou a versão)

**Sem o cabo USB.** Quase tudo daqui se faz sem cabo e sem adb: o APK vai ao aparelho por arquivo ou por link, e a
instalação é do próprio dono do aparelho. O que precisa do cabo está marcado "(com o cabo)", e o resto não depende dele.

- [ ] Instalar o APK de depuração por arquivo (`android/app/build/outputs/apk/debug/app-debug.apk`, mandado ao próprio
      aparelho) ou o da Release pelo link. O APK de depuração da mesma máquina instala por cima do anterior sem perder os
      dados (a chave de depuração é a mesma); de outra máquina, ou trocando entre depuração e Release, o Android pede
      para desinstalar antes, e desinstalar apaga o espelho.
- [ ] O APK de depuração nunca vai a técnico: no campo, só o da Release.

## O roteiro da spec (10.3)

- [ ] Partida em modo avião depois da primeira abertura: o App abre com o que tinha.
- [ ] Partida em modo avião logo depois de uma troca de versão do `sw.js` (combinar a troca com a sessão "App de campo").
- [ ] Login com MFA dentro do app; anotar os hosts por onde o login passou (lista do `allowNavigation`; com o cabo, o
      logcat do app mostra cada navegação, sem os parâmetros).
- [ ] Login ainda válido depois de 25 h (os cookies de terceiros ligados).
- [ ] Conectar o Fracttal e voltar ao app com a conta reconhecida.
- [ ] Foto pela câmera interna do App.
- [ ] Foto pela reserva do `<input capture>` (a câmera do sistema).
- [ ] Vídeo.
- [ ] GPS com localização precisa.
- [ ] GPS com localização aproximada: aparece o aviso de liberar a precisa.
- [ ] Fechar a OS de teste sem sinal, voltar o sinal e ver a fila subir sozinha (evento `online`).
- [ ] Matar o app no meio de uma gravação (tirar dos recentes) e ver, ao abrir, que nada se perdeu.
- [ ] Apagar o armazenamento da WebView numa versão de teste e ver o espelho devolver a fila e as fotos (abaixo).
- [ ] Atualizar a casca pelo `versao.json` (abaixo).

## Apagar o armazenamento da WebView (só no APK de depuração)

"Limpar armazenamento" nos ajustes do Android apaga o espelho junto e não serve para esta prova. O APK de depuração traz
um caminho que apaga só o armazenamento da WebView (o localStorage e o IndexedDB de todas as origens; os cookies ficam) e
deixa o espelho nativo intacto. O APK da Release não tem esse caminho (o `testes/depuracao.test.mjs` confere).

- [ ] Com internet, entrar com o usuário de teste, abrir a OS de teste, deixar um rascunho, tirar duas fotos e deixar ao
      menos um item na fila (por exemplo, fechar uma tarefa em modo avião e voltar o sinal só depois da prova).
- [ ] Tirar o App dos recentes (a tela tem de abrir do zero: com ela aberta, o atalho só traz o App para a frente).
- [ ] Tocar e segurar o ícone do App e escolher **Prova: apagar WebView**.
      Com o cabo, só num aparelho em que o shell do adb é permitido (no aparelho de prova não é: lá o adb só instala,
      desinstala e lê o logcat do app), o mesmo pedido vai por
      `adb shell am start -n br.com.gridco.campo/.MainActivity --ez gc_apagar_webview true`.
- [ ] Aparece o aviso "Depuração: armazenamento da WebView apagado..." e, uns 3 s depois, o App abre sozinho.
- [ ] O App pode pedir o login de novo: é esperado (a sessão da Microsoft nunca vai ao espelho) e é também o sinal de
      que o armazenamento foi mesmo apagado.
- [ ] Depois do login, o rascunho, as duas fotos e a fila estão de volta, e a fila sobe com o sinal.
- [ ] (com o cabo) O logcat do app mostra o pedido do retrato da perda e o "restaurado" com a contagem de chaves, sem
      nome de chave nem valor.

## O voltar do Android

- [ ] Com a OS aberta, o voltar (gesto ou botão) fecha a OS e o App continua na lista.
- [ ] Com uma folha, a câmera interna, a foto ampliada ou a APR abertas por cima da OS, cada voltar fecha só a de cima.
- [ ] Na APR, o rascunho continua lá depois do voltar.
- [ ] Sem nada aberto, o voltar manda o App para o fundo; ao voltar pelo ícone ou pelos recentes, a página está onde
      estava, sem recarregar.
- [ ] Na tela "Sem internet" e no login da Microsoft, o voltar manda o App para o fundo (nunca fecha).

## A atualização da casca

- [ ] Com uma Release mais nova no ar, o aviso "Nova versão do App de Campo" aparece ao abrir (no máximo uma vez a cada
      6 h). O APK de depuração nunca recebe a oferta (a assinatura dele é outra): esta prova é com o APK da Release.
- [ ] "Atualizar agora" mostra o aviso rápido "Baixando a versão nova…".
- [ ] Sem a permissão de instalar apps desconhecidos: "Abrir ajustes", liberar, voltar, e o instalador abre.
- [ ] Sair do App durante o download (ir à tela inicial) e voltar depois de ele terminar: o instalador abre na volta.
- [ ] Com o sinal cortado no meio do download, aparece o aviso de que a atualização não deu certo.

## O que não se vê no aparelho sem ferramenta

- A faxina das fotos da reserva da câmera (as de mais de 48 h saem da pasta do app na abertura): a pasta não aparece sem
  o cabo, e o teste de JVM (`FaxinaDaCameraTest`) é a prova.
- O teto das recriações (o renderer que morre 4 vezes em 60 s mostra a tela "Sem internet" em vez de piscar): não há
  como provocar sem ferramenta; o teste de JVM (`TetoDeRecriacaoTest`) e a montagem são a prova.
- A WebView antiga (sem os recursos de que a casca precisa): só num aparelho com a WebView desatualizada.
