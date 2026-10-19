# Prova no aparelho

O roteiro da seção 10.3 da spec (`docs/superpowers/specs/2026-10-16-app-campo-android-design.md`), em caixas de seleção,
mais o que as correções de 17/10/2026 pedem para ver num Android de verdade. Marque cada caixa só depois de ver
acontecer; o que falhar vira uma nota embaixo do item (o que se fez, o que se viu, a hora), sem nome de pessoa.

## Antes de começar

- Usuário de teste combinado com o PCM: `________________` (o responsável pela prova preenche; nunca um técnico real)
- OS de teste combinada com o PCM: `________________` (nenhuma OS real é usada)
- Conta de teste com papel Admin no App de Campo (as notificações): `________________`
- Conta de teste sem Admin, de um técnico (as notificações): `________________`
- Aparelho: `________________` (modelo e versão do Android)
- APK: `________________` (depuração ou Release, e o commit ou a versão)

**Sem o cabo USB.** Quase tudo daqui se faz sem cabo e sem adb: o APK vai ao aparelho por arquivo ou por link, e a
instalação é do próprio dono do aparelho. O que precisa do cabo está marcado "(com o cabo)", e o resto não depende dele.

- [ ] Instalar o APK de depuração por arquivo (`android/app/build/outputs/apk/debug/app-debug.apk`, mandado ao próprio
      aparelho) ou o da Release pelo link. O APK de depuração da mesma máquina instala por cima do anterior sem perder os
      dados (a chave de depuração é a mesma); de outra máquina, ou trocando entre depuração e Release, o Android pede
      para desinstalar antes, e desinstalar apaga o espelho.
- [ ] O APK de depuração nunca vai a técnico: no campo, só o da Release.
- [ ] Um APK de antes de 17/10/2026 (o pacote `br.com.gridco.campo`, "App de Campo Grid Co.") é outro aplicativo para o
      Android: o Nexus Field (`br.com.gridco.nexusfield`) instala ao lado dele, com o espelho vazio. O antigo sai pelo
      desinstalar de sempre, e o que só ele tinha (fila, fotos) sai junto: confira antes que nada ficou por enviar.

## Ícone, nome e abertura

- [ ] Na tela inicial, o ícone é o G da Grid em branco sobre o navy, inteiro (sem corte) na máscara do aparelho, com o
      rótulo **Nexus Field** embaixo. Na gaveta de apps e em Configurações > Apps, o mesmo nome.
- [ ] Com os ícones temáticos ligados (Android 13+, "Ícones com tema" no papel de parede), o G aparece na cor do tema.
- [ ] Ao abrir, a abertura é o navy com o G pequeno no centro (sem a imagem padrão do Capacitor).
- [ ] A tela "Sem internet" (primeira abertura em modo avião) tem o mesmo navy da abertura, não o roxo-escuro do site, com o
      texto legível e o botão verde "Tentar de novo".

## O roteiro da spec (10.3)

- [ ] Partida em modo avião depois da primeira abertura: o Nexus Field abre com o que tinha.
- [ ] Partida em modo avião logo depois de uma troca de versão do `sw.js` (combinar a troca com a sessão "App de campo").
- [ ] Login com MFA dentro do app; anotar os hosts por onde o login passou (lista do `allowNavigation`; com o cabo, o
      logcat do app mostra cada navegação, sem os parâmetros).
- [ ] Login ainda válido depois de 25 h (os cookies de terceiros ligados).
- [ ] Conectar o Fracttal e voltar ao app com a conta reconhecida.
- [ ] Foto pela câmera interna do App de Campo.
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
- [ ] Tirar o Nexus Field dos recentes (a tela tem de abrir do zero: com ela aberta, o atalho só traz o Nexus Field
      para a frente).
- [ ] Tocar e segurar o ícone do Nexus Field e escolher **Prova: apagar WebView**.
      Com o cabo, só num aparelho em que o shell do adb é permitido (no aparelho de prova não é: lá o adb só instala,
      desinstala e lê o logcat do app), o mesmo pedido vai por
      `adb shell am start -n br.com.gridco.nexusfield/br.com.gridco.campo.MainActivity --ez gc_apagar_webview true` (a
      classe vai com o nome inteiro: o pacote Java não é o applicationId).
- [ ] Aparece o aviso "Depuração: armazenamento da WebView apagado..." e, uns 3 s depois, a página do App de
      Campo abre sozinha.
- [ ] O App de Campo pode pedir o login de novo: é esperado (a sessão da Microsoft nunca vai ao espelho) e é também o
      sinal de que o armazenamento foi mesmo apagado.
- [ ] Depois do login, o rascunho, as duas fotos e a fila estão de volta, e a fila sobe com o sinal.
- [ ] (com o cabo) O logcat do app mostra o pedido do retrato da perda e o "restaurado" com a contagem de chaves, sem
      nome de chave nem valor.

## O voltar do Android

- [ ] Com a OS aberta, o voltar (gesto ou botão) fecha a OS e o App de Campo continua na lista.
- [ ] Com uma folha, a câmera interna, a foto ampliada ou a APR abertas por cima da OS, cada voltar fecha só a de cima.
- [ ] Na APR, o rascunho continua lá depois do voltar.
- [ ] Sem nada aberto, o voltar manda o Nexus Field para o fundo; ao voltar pelo ícone ou pelos recentes, a página está
      onde estava, sem recarregar.
- [ ] Na tela "Sem internet" e no login da Microsoft, o voltar manda o Nexus Field para o fundo (nunca
      fecha).

## A atualização da casca

- [ ] Com uma Release mais nova no ar, o aviso "Nova versão do Nexus Field" aparece ao abrir (no máximo uma vez a
      cada 6 h). O APK de depuração nunca recebe a oferta (a assinatura dele é outra): esta prova é com o APK da Release.
- [ ] "Atualizar agora" mostra o aviso rápido "Baixando a versão nova…".
- [ ] Sem a permissão de instalar apps desconhecidos: "Abrir ajustes", liberar, voltar, e o instalador abre.
- [ ] Sair do Nexus Field durante o download (ir à tela inicial) e voltar depois de ele terminar: o instalador abre na
      volta.
- [ ] Com o sinal cortado no meio do download, aparece o aviso de que a atualização não deu certo.

## As notificações (1.1.0)

O servidor do App de Campo escreve o título e o texto de cada notificação (a casca só os mostra), então aqui se confere
que os avisos chegam, no canal certo e com o toque certo, e não o texto exato: o texto muda no servidor sem a casca mudar.
A OS dos avisos de teste é a fictícia `12345`: ela não existe no Fracttal, e o que se confere no toque é a aba Tarefas
aberta com `12345` na busca. As telas e os textos de cada aviso estão na prévia (`docs/previas/telas.html`, seção
Notificações). Precisa de duas contas de teste, combinadas com o PCM: uma Admin e uma sem Admin (um técnico), preenchidas
em "Antes de começar".

### Antes de tocar em qualquer aviso

- [ ] O APK é o da Release 1.1.0, ou um de depuração montado com o `google-services.json` do dono da casca em
      `android/app/`. Um APK sem esse arquivo abre e funciona como antes, mas não recebe aviso e não mostra os atalhos de
      teste.
- [ ] As rotas do servidor estão no ar (v286 ou mais nova): `/me`, `/push/registrar`, `/push/esquecer` e `/push/teste`.
      Conferir com a sessão "App de campo".
- [ ] Os textos novos da prévia (os avisos, os canais, os atalhos e os avisos rápidos) estão aprovados pelo dono da casca.
      A tag `v1.1.0` só sai com essas três coisas.

### A permissão de notificação

- [ ] Instalação nova (Android 13 ou mais novo): o **Antes de começar** fala das notificações e, tocando em
      **Continuar**, o Android pede a câmera, a localização e, por último, a notificação.
- [ ] Atualização por cima da 1.0.0 (Android 13 ou mais novo): ao abrir, aparece **Avisos do Nexus Field** uma vez só;
      tocar fora e o Voltar não o fecham, e **Continuar** leva ao pedido do Android.
- [ ] Negar o pedido: o Nexus Field não pergunta de novo (ligar depois é nos ajustes do Android) e nenhum aviso aparece.
- [ ] Permitir: nos ajustes do Android (Apps, Nexus Field, Notificações) estão os três canais, **Resumo do dia**,
      **Emergências** e **Emergências fora do expediente**.
- [ ] Android 12 ou mais velho (se houver aparelho): nenhum pedido aparece, e os avisos chegam.

### O registro do aparelho e o atalho de teste (só Admin)

- [ ] Entrar com a conta Admin: o toque longo no ícone mostra **Testar avisos** e **Testar fora do expediente** (podem
      levar alguns segundos depois da abertura, com internet).
- [ ] Entrar com a conta sem Admin (passando pelo Sair): o toque longo não mostra nenhum dos dois. Depois do Sair da conta
      Admin, eles também saem.
- [ ] Com a conta Admin, **Testar avisos**: o aviso rápido diz **Aviso de teste enviado para 1 aparelho(s)**. Se disser
      0, o aparelho não ficou registrado no servidor: anotar a hora e o que se fez antes.
- [ ] Sair e entrar de novo com a mesma conta: o teste volta a dizer 1 aparelho (cada abertura registra o aparelho de
      novo, com a conta de agora).
- [ ] **Testar avisos** manda um resumo e uma emergência de teste: o resumo chega no canal **Resumo do dia**, e a
      emergência (título **Emergência**, OS 12345) no canal **Emergências**: aparece por cima da tela, toca o alarme do
      aparelho e vibra três vezes.
- [ ] **Testar fora do expediente**: a emergência de teste chega no canal **Emergências fora do expediente**, na barra,
      sem som, sem vibração e sem aparecer por cima da tela.
- [ ] Dois toques seguidos num atalho, em menos de 10 s, mandam um teste só.
- [ ] Com a rede cortada, o toque no atalho mostra **Não consegui enviar o teste agora** (em até 20 s).
- [ ] (opcional) Fixar um atalho na tela inicial (arrastar do menu do toque longo) e tocar nele com ninguém logado:
      **Entre no Nexus Field para testar**. Entrar com a conta sem Admin e tocar nele: **Só administrador pode testar**,
      e os dois atalhos do ícone saem. O atalho fixado não some sozinho com o Sair (limite conhecido).

### Os canais e o ícone

- [ ] O ícone pequeno na barra de cima é o G da Grid em branco (não um quadrado liso), com o Android nos dois temas.
- [ ] Segurar a notificação mostra o canal certo. Mudar o som de **Emergências** nos ajustes do Android e abrir o Nexus
      Field de novo: o ajuste do técnico continua.
- [ ] **Testar avisos** duas vezes (com mais de 10 s entre elas), a primeira ainda na barra: fica uma emergência só (a
      mesma OS substitui a anterior) e ela não toca de novo; o resumo também fica um só, e esse toca de novo.

### O toque na emergência

Use **Testar avisos**. A regra é que o toque nunca interrompe o técnico.

- [ ] Nexus Field fechado (tirado dos recentes): tocar na emergência abre o Nexus Field e, quando a página termina de
      abrir, a aba Tarefas está com `12345` na busca.
- [ ] No fundo, na aba Tarefas, sem nada aberto: tocar abre já, com `12345` na busca.
- [ ] No fundo, em outra aba (a Ronda, na lista, sem ronda em andamento), sem nada aberto: tocar troca para a aba Tarefas e
      põe `12345` na busca.
- [ ] Com uma OS aberta (preenchendo): tocar traz o Nexus Field para a frente sem fechar a OS nem trocar de aba, e a OS
      fica pendente. Ao fechar a OS, em até uns 2 s a aba Tarefas abre com `12345` na busca.
- [ ] No meio de uma ronda: igual, a OS fica pendente até a ronda ser fechada.
- [ ] Com a câmera interna, uma folha ou a APR abertas por cima da OS: o toque não fecha nenhuma delas, e a OS fica
      pendente até ele fechar tudo.
- [ ] Anotar o que se vê, sem passa ou falha: com uma OS pendente, tirar o Nexus Field dos recentes e abrir de novo. A
      pendência não sobrevive ao app fechado (limite conhecido), e a OS não abre sozinha.
- [ ] Tocar no resumo abre o Nexus Field onde ele estava, sem mexer na tela.

### O resumo das 7h e das 13h

Depende do servidor e do dia, e de um usuário de teste com tarefa, combinado com o PCM.

- [ ] Num dia com tarefa para o usuário de teste, às 7h chega o resumo da manhã (título **Bom dia!**, com as contagens do
      dia) e às 13h o da tarde (título **Boa tarde!**, só com o que falta); o das 13h toma o lugar do das 7h na barra.
- [ ] Num dia sem tarefa para o usuário de teste, nenhum dos dois chega.
- [ ] Com o Nexus Field fechado (tirado dos recentes) e a tela apagada, os dois chegam do mesmo jeito. Se a economia de
      bateria do fabricante segurar o aviso, anotar o modelo e a hora.
- [ ] Sem sinal na hora do envio, o aviso chega quando o sinal voltar (risco aceito na spec).
- [ ] (com o cabo) O logcat do app mostra `notificação mostrada (resumo)` ou `(emergencia)` e `atalhos de teste dos avisos
      publicados`, sem título, texto nem número de OS.

## O que não se vê no aparelho sem ferramenta

- O Android 7, que não tem canais de notificação: lá a prioridade da própria notificação faz o papel do canal (som e
  vibração na emergência, só som no resumo, nada na silenciosa). Só num aparelho com Android 7.
- A faxina das fotos da reserva da câmera (as de mais de 48 h saem da pasta do app na abertura): a pasta não aparece sem
  o cabo, e o teste de JVM (`FaxinaDaCameraTest`) é a prova.
- O teto das recriações (o renderer que morre 4 vezes em 60 s mostra a tela "Sem internet" em vez de piscar): não há
  como provocar sem ferramenta; o teste de JVM (`TetoDeRecriacaoTest`) e a montagem são a prova.
- A WebView antiga (sem os recursos de que a casca precisa): só num aparelho com a WebView desatualizada.
