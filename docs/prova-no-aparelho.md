# Prova no aparelho

O roteiro da seção 10.3 da spec (`docs/superpowers/specs/2026-10-16-app-campo-android-design.md`), em caixas de seleção,
mais o que as correções de 17/10/2026 pedem para ver num Android de verdade. Marque cada caixa só depois de ver
acontecer; o que falhar vira uma nota embaixo do item (o que se fez, o que se viu, a hora), sem nome de pessoa.

## Antes de começar

O repositório é público: quem é o usuário de teste, as contas e a OS de teste (o nome, o e-mail, o número) nunca entram
neste arquivo. Anote numa cópia fora do git.

- Usuário de teste combinado com o PCM (nunca um técnico real), com tarefa no dia da prova.
- OS de teste combinada com o PCM: uma OS que existe no Fracttal, atribuída ao usuário de teste (nenhuma OS de trabalho
  real é usada).
- Para as notificações, duas contas de teste combinadas com o PCM: uma com o papel Admin no App de Campo e o usuário de
  teste (sem Admin). Nunca a conta de um técnico real: o aparelho de prova passaria a receber os avisos dele. A conta
  Admin entra só no aparelho de prova (e no segundo aparelho, quando a prova pedir): o aviso rápido do teste conta os
  aparelhos da conta, e o "1 aparelho(s)" só quer dizer "este aparelho" assim.
- A conta Admin de prova com trabalho de prova na lista dela, combinado com o PCM: uma tarefa (uma OS de prova) no dia,
  para abrir a OS e o Concluir, a ronda de uma usina de prova e um serviço da Zeladoria. O toque na emergência é provado
  na conta que recebe o teste dos avisos, que é a Admin; sem esse trabalho, os itens da OS aberta, da ronda começada e
  do serviço da Zeladoria não têm como ser feitos.
- Para a busca ao vivo, outra OS de prova, ABERTA no Fracttal e ainda fora da lista: o PCM a atribui à conta que estiver
  no Nexus Field só depois de ele abrir (a OS que já está na lista do dia não prova a busca ao vivo).
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

O servidor do App de Campo escreve o título e o texto de cada notificação (o Nexus Field só os mostra), então aqui se
confere que os avisos chegam, no canal certo, com o som certo e com o toque certo, e não o texto exato: o texto muda no
servidor sem o Nexus Field mudar. Os avisos de teste saem do atalho **Testar avisos**, que só a conta Admin vê no toque
longo do ícone: o servidor manda um resumo e uma emergência de mentira aos aparelhos da conta, sem criar nada no
Fracttal, e a emergência traz sempre a mesma OS, a `999999999` (fora da faixa das OS reais; no toque, o que se confere é
o número na busca da aba Tarefas, e o que a busca encontra para ele não faz parte desta prova). As telas e os textos de
cada aviso estão na prévia (`docs/previas/telas.html`, seção Notificações). Siga as subseções na ordem: num aparelho só,
a atualização e a permissão vêm primeiro, e cada subseção deixa o aparelho pronto para a seguinte.

"Tirar dos recentes" fecha a tela do Nexus Field, mas alguns aparelhos mantêm o processo dele vivo por um tempo. Os itens
que dependem disso dizem o que se espera nos dois casos; se der para saber (com o cabo, o logcat do app mostra a partida
do processo), anotar qual foi.

### Antes de tocar em qualquer aviso

- [ ] O aparelho de prova tem a Release 1.0.0 (instalada pelo link) com a câmera e a localização PRECISA já liberadas: em
      Configurações > Apps > Nexus Field > Permissões, a Câmera permitida e a Localização permitida com "Usar localização
      precisa" ligado. Com a aproximada, a 1.1.0 abre com o **Antes de começar** (que pede tudo de novo) no lugar do
      **Avisos do Nexus Field**, e a prova da atualização não acontece.
- [ ] As rotas do servidor estão no ar (v286 ou mais nova; a OS `999999999` do teste é da v287): `/me`,
      `/push/registrar`, `/push/esquecer` e `/push/teste`. Conferir com a sessão "App de campo".
- [ ] A Release 1.1.0 está publicada. A tag `v1.1.0` sai depois dos textos da prévia aprovados pelo dono da casca, do
      `google-services.json` no repositório (com a chave restrita: ver o `CLAUDE.md`, "A ordem de dependência") e das
      rotas no ar. Se outro aparelho além do de prova tiver a 1.0.0, a Release final chegaria a ele também, antes da
      prova: aí sai antes o pré-lançamento (`v1.1.0-rc.1`), que o aviso de atualização não oferece a ninguém, instalado
      por arquivo por cima da 1.0.0 (a mesma chave: atualiza sem perder o espelho), e a tag final só depois da prova.
- [ ] Um APK de depuração só serve a partir de "O registro do aparelho": ele não instala por cima da Release (a
      assinatura é outra) nem recebe o aviso de atualização. Montado sem o `google-services.json`, ele não recebe aviso
      nenhum: os atalhos de teste aparecem para a conta Admin mesmo assim (o papel da conta não depende do Firebase), e o
      aviso rápido do teste conta só os outros aparelhos da conta.
- [ ] Um segundo aparelho Android com o Nexus Field 1.1.0, para as provas que pedem o aviso mandado de fora (o Nexus
      Field fechado, o Sair, o aviso que chega depois do expediente). A conta Admin entra nele só nessas provas. Sem ele,
      pedir o envio de teste à sessão "App de campo".

### A atualização e a permissão (nesta ordem)

- [ ] Abrir o Nexus Field 1.0.0 com internet: aparece **Nova versão do Nexus Field**, com a versão 1.1.0 e as duas
      novidades da prévia. (A 1.0.0 confere a cada 6 h: se não aparecer, abrir de novo mais tarde.)
- [ ] **Atualizar agora**: o aviso rápido **Baixando a versão nova…** e, depois, o instalador do Android (antes dele,
      **Permitir a atualização**, se for a primeira vez). Atualizar e abrir. Este é o primeiro token do Firebase do
      aparelho (a 1.0.0 não tinha Firebase): é ele que confere a restrição da chave da Release (`CLAUDE.md`, "A chave de API
      do google-services.json").
- [ ] Na primeira abertura da 1.1.0 (Android 13 ou mais novo) aparece **Avisos do Nexus Field**, uma vez só; tocar fora
      e o Voltar não o fecham, e **Continuar** leva ao pedido do Android. (No Android 12 ou mais velho não há aviso nem
      pedido e as notificações já vêm ligadas: pule só os dois itens seguintes, o de negar e o da reabertura; no da conta
      Admin, o teste já diz **Aviso de teste enviado para 1 aparelho(s)** e os avisos chegam; o dos ajustes vale para ver os
      três canais.)
- [ ] No pedido do Android, negar ("Não permitir").
- [ ] Tirar o Nexus Field dos recentes e abrir de novo: nada é perguntado, nem o aviso nem o pedido.
- [ ] Entrar com a conta Admin (pelo "Entrar" da página, se ela pedir). Em alguns segundos, o toque longo no ícone mostra
      **Testar avisos** e **Testar sem alarme**. **Testar avisos**: o aviso rápido diz **Aviso de teste
      enviado para 0 aparelho(s)** (ou o número de outros aparelhos em que a conta Admin entrou), e nada aparece neste:
      com a notificação negada, o Nexus Field nem se registra no servidor.
- [ ] Ligar a notificação em Configurações > Apps > Nexus Field > Notificações e voltar ao Nexus Field: ele se registra
      sozinho em alguns segundos. **Testar avisos** de novo: **Aviso de teste enviado para 1 aparelho(s)**, e o resumo e a
      emergência de teste chegam. Nos mesmos ajustes estão os três canais, **Resumo do dia**, **Emergências** e
      **Emergências fora do expediente**. Um quarto, **Localização da equipe** (1.2.0), só aparece no aparelho em que a
      coleta da posição da equipe já ligou, um quinto, **Início do dia** (1.2.0, o aviso das 6h), no aparelho com a
      chave da equipe e o termo de uso aceito, e um sexto, **Ajustes da localização** (1.2.3, o aviso do que falta), no
      aparelho do piloto em que já faltou liberar algo para a coleta.
- [ ] Ainda nos ajustes, o som de **Emergências** é um som de alarme do aparelho (o Android mostra o nome do toque do
      despertador, ou algo como "Som de alarme padrão"), e não o som padrão de notificação. Se não for, o aparelho rodou um
      APK de depuração deste trabalho de antes do alarme (pela Release isso não acontece): o canal antigo não muda
      sozinho, e só reinstalar o refaz. Desinstalar apaga o espelho: reinstalar só depois de conferir que nada ficou na
      fila (nada por enviar no App de Campo, nenhum rascunho).

### Antes de cada teste

Vale para todo item das subseções abaixo.

- [ ] Dispensar da barra (arrastar para o lado) os avisos do Nexus Field que estiverem lá. A emergência de teste tem
      sempre o mesmo número e o mesmo lugar na barra: a que chega com a anterior ainda lá só a substitui, sem tocar, sem
      vibrar e sem aparecer por cima (e o resumo de teste igual ao da barra também não toca). Sem isso, o teste falha num
      canal que está certo.
- [ ] O aparelho fora do modo silencioso e do Não perturbe, com o volume de notificação ligado: o alarme das emergências
      toca no volume das notificações, e não no do despertador.
- [ ] Num Xiaomi ou Redmi, ligar as notificações flutuantes do Nexus Field (em Apps > Nexus Field > Notificações, no app
      e no canal **Emergências**; o nome do menu muda com a versão do MIUI e do HyperOS). Elas vêm desligadas para app
      instalado fora da loja, e sem elas a emergência não aparece por cima da tela. Anotar se vinham desligadas: é o que
      cada técnico com Xiaomi vai encontrar.

### O registro do aparelho e o atalho de teste (só Admin)

- [ ] O toque no atalho abre o Nexus Field (o atalho entra por uma porta interna do aplicativo) e, logo depois, aparece
      o aviso rápido. Se o atalho não abrir o Nexus Field, anotar a marca do aparelho e o launcher: é um achado para quem
      desenvolve o Nexus Field.
- [ ] Entrar com a conta sem Admin (o usuário de teste), passando pelo Sair: o toque longo não mostra nenhum dos dois
      atalhos. Depois do Sair da conta Admin, eles também saem.
- [ ] Sair e entrar de novo com a conta Admin: o teste volta a dizer **Aviso de teste enviado para 1 aparelho(s)** (cada
      abertura registra o aparelho de novo, com a conta de agora). Se disser 0 com a notificação ligada, o aparelho não
      ficou registrado: anotar a hora e o que se fez antes.
- [ ] Num dia útil, das 7h às 17h (hora de Brasília), **Testar avisos**: o resumo chega no canal **Resumo do dia** (com o
      som padrão), e a emergência (título **Emergência**, OS 999999999) no canal **Emergências**: aparece por cima da
      tela, toca o alarme do aparelho e vibra três vezes. Fora desse horário ela chega em silêncio (ver "A emergência fora
      do expediente", abaixo).
- [ ] **Testar sem alarme**: a emergência de teste chega no canal **Emergências fora do expediente**, na barra, sem som,
      sem vibração e sem aparecer por cima da tela. O resumo de teste que vem junto TOCA, com o som normal, no **Resumo do
      dia**: o "sem alarme" é só da emergência.
- [ ] Dois toques seguidos num atalho, em menos de 10 s: o segundo mostra **Teste já pedido. Espere alguns segundos para
      pedir outro**, e chega um par de avisos só.
- [ ] Com a rede cortada, o toque no atalho mostra **Não consegui enviar o teste agora** (em até 20 s), um aviso só.
- [ ] Com a conta Admin no aparelho (sem o Sair), tirar o Nexus Field dos recentes, tocar no atalho e não entrar: em uns
      20 s, **Entre no Nexus Field para testar**.
- [ ] (opcional) Fixar um atalho na tela inicial (arrastar do menu do toque longo). Depois do Sair, tocar nele: o Android
      mostra **Só para administrador** e o Nexus Field não abre; com a conta sem Admin, o mesmo. Com a conta Admin de
      novo, ele volta a testar.
- [ ] (opcional, com a sessão "App de campo") Tirar o papel Admin da conta no servidor com o Nexus Field aberto e tocar no
      atalho: **Só administrador pode testar**, e os atalhos saem na hora (o fixado fica desligado).

### Os canais, o som e o Não perturbe

- [ ] O ícone pequeno na barra de cima é o G da Grid em branco (não um quadrado liso), com o Android nos dois temas.
- [ ] Segurar a notificação mostra o canal certo. Mudar o som de **Emergências** nos ajustes do Android e abrir o Nexus
      Field de novo: o ajuste do técnico continua.
- [ ] **Testar avisos** duas vezes (com mais de 10 s entre elas), desta vez SEM dispensar a primeira: fica uma
      emergência só (a mesma OS substitui a anterior) e ela não toca de novo; o resumo também fica um só e, com o mesmo
      texto na barra, também não toca de novo.
- [ ] Com o Não perturbe ligado, **Testar avisos** (no expediente): a emergência não toca nem vibra (o Nexus Field
      respeita o Não perturbe; conforme o ajuste dele, ela fica só na barra ou aparece quando ele sair). No modo
      silencioso, o mesmo; no modo vibrar, ela só vibra. Desligar o Não perturbe e voltar o som antes do próximo item.

### A emergência fora do expediente

- [ ] Depois das 17h, antes das 7h ou no fim de semana (hora de Brasília), **Testar avisos**: a emergência de teste
      chega no canal **Emergências fora do expediente**, sem tocar, mesmo pedida no canal alto. É a regra da emergência
      que CHEGA fora do expediente: a enviada às 16h50 a um celular sem sinal, que só pega sinal às 19h40 em casa, não
      toca.
- [ ] (com o segundo aparelho) Num dia útil, pouco antes das 17h, pôr o aparelho de prova no modo avião; no outro,
      **Testar avisos**; depois das 17h, tirar o modo avião: a emergência chega ao de prova no canal **Emergências fora do
      expediente**, sem tocar, e a hora dela na barra é a do envio (antes das 17h). Se ela não chegar, conferir com a
      sessão "App de campo" quanto tempo o aviso de teste espera pelo sinal.

### O toque na emergência

Use **Testar avisos** no expediente, com a conta Admin (que tem o trabalho de prova: ver "Antes de começar"). A regra é que
o toque nunca interrompe o técnico: ocupado, a OS fica pendente e vai à busca sozinha quando ele ficar livre, e a
emergência fica na barra até a OS ser mostrada. A emergência de teste traz sempre a mesma OS, e cada toque vale como um
pedido novo, mesmo com ela já mostrada num item anterior (é o caso de quem toca de novo numa OS que já viu): não é preciso
recarregar o Nexus Field entre os itens. A emergência sai da barra um pouco depois de a OS aparecer na busca (o Nexus
Field confere, cerca de 1,5 s depois, que ela ficou à vista).

- [ ] Antes de cada item desta subseção: a barra sem avisos do Nexus Field (como em "Antes de cada teste") e a busca da
      aba Tarefas vazia (o × ao lado do campo de busca). Com o número de um item anterior ainda na busca, não se vê se
      foi este toque que o pôs lá.
- [ ] No fundo, na aba Tarefas, sem nada aberto: tocar abre já, com `999999999` na busca, e a emergência sai da barra.
- [ ] No fundo, em outra aba sem nada aberto (a lista da Zeladoria, por exemplo): tocar troca para a aba Tarefas e põe
      `999999999` na busca.
- [ ] Na aba Ronda, na primeira etapa, sem escolher a usina nem responder nada (a ronda não começou): tocar troca para a
      aba Tarefas e põe `999999999` na busca.
- [ ] Na aba Ronda, com a usina escolhida ou uma resposta dada (a ronda começou): tocar traz o Nexus Field para a frente
      sem sair da Ronda, a OS fica pendente e a emergência continua na barra. Ao sair da Ronda (para outra aba) ou ao
      finalizá-la, em até uns 2 s a aba Tarefas abre com `999999999` na busca.
- [ ] Com uma OS aberta (preenchendo): tocar traz o Nexus Field para a frente sem fechar a OS nem trocar de aba, e a OS
      fica pendente. Ao fechar a OS, em até uns 2 s a aba Tarefas abre com `999999999` na busca.
- [ ] Com um serviço da Zeladoria aberto (preenchendo): igual, a OS fica pendente até ele voltar à lista.
- [ ] Com a câmera interna, uma folha ou a APR abertas por cima da OS: o toque não fecha nenhuma delas, e a OS fica
      pendente até ele fechar tudo.
- [ ] Nexus Field fechado (tirado dos recentes): tocar abre o Nexus Field na tela de entrada do App de Campo, e a OS
      espera. Tocar em "Entrar": depois da entrada e de a fila carregar, a aba Tarefas abre com `999999999` na busca, e a
      emergência sai da barra. Vale com o processo morto ou vivo.
- [ ] A OS que abre depois da fila: com o Concluir de uma tarefa aberto (preenchendo), tirar o Nexus Field dos recentes,
      tocar na emergência e "Entrar". A tela do App aparece antes da fila; quando a fila chega, o App de Campo reabre o
      Concluir em que ele estava ("Você estava aqui"), e a OS espera, sem ir à busca por baixo dele. Ao fechar o Concluir,
      em até uns 2 s a aba Tarefas abre com `999999999` na busca. Se a fila demorar mais de uns 20 s depois da entrada, a
      OS vai à busca assim mesmo; anotar se o Concluir foi reaberto por cima dela.
- [ ] Com uma OS pendente, tirar o Nexus Field dos recentes e abrir pelo ícone. Se o processo morreu, a OS não abre
      sozinha (a pendência era daquela partida) e a emergência continua na barra; tocar nela de novo leva à OS (depois do
      "Entrar"), e aí ela sai da barra. Se o processo sobreviveu, a OS abre sozinha depois do "Entrar" (o Nexus Field a
      guardou), e a emergência sai da barra.
- [ ] Com a câmera do sistema aberta pela reserva (a foto pela reserva do `<input capture>`, de "O roteiro da spec"),
      tocar na emergência: a câmera fecha e a foto que estava sendo tirada se perde. É limite conhecido (o Android fecha o
      que está por cima do Nexus Field quando o traz para a frente): a foto se tira de novo. Anotar se foi assim.
- [ ] A busca ao vivo com uma OS que existe: o PCM atribui uma OS ABERTA, fora da lista do dia, à conta que está no Nexus
      Field DEPOIS de ele abrir (ver "Antes de começar"); digitar o número dela na busca da aba Tarefas, que é o que o
      toque faz: o cartão dela aparece, trazido do Fracttal.
- [ ] Tocar no resumo abre o Nexus Field onde ele estava, sem mexer na tela, e o resumo sai da barra.

### O app fechado, o Sair e quem recebe (com o segundo aparelho)

Com o segundo aparelho na mesma conta Admin, ou com o envio de teste pedido à sessão "App de campo". Com a conta Admin
nos dois aparelhos, o aviso rápido do teste conta os dois.

- [ ] A emergência com o Nexus Field fechado: no aparelho de prova, tirar o Nexus Field dos recentes e apagar a tela; no
      outro, **Testar avisos** (no expediente): com a tela apagada, a emergência chega ao de prova tocando o alarme e
      vibrando (com a tela apagada não há aviso por cima); ao acender a tela, ela está na barra.
- [ ] O esquecer do Sair: no aparelho de prova, com internet, Sair (os avisos do Nexus Field saem da barra dele); no
      outro, **Testar avisos**: o aviso rápido conta um aparelho a menos, e nada chega ao de prova.
- [ ] O Sair sem rede: entrar de novo no de prova com a conta Admin, pôr o modo avião, Sair e tirar o modo avião sem
      entrar com conta nenhuma; no outro, **Testar avisos**: nada aparece no de prova (o aviso rápido pode ainda contá-lo:
      o servidor não soube do Sair, mas o Nexus Field fica calado até alguém entrar). Entrar de novo no de prova: os
      avisos voltam.
- [ ] As marcas da frota: num aparelho de prova de cada marca (Xiaomi ou Redmi, Samsung e Motorola; nunca o aparelho de
      um técnico), com a conta Admin, tirar o Nexus Field dos recentes e apagar a tela por 30 min ou mais; de outro
      aparelho, **Testar avisos**: o aviso chega. Se não chegar, anotar a marca, o modelo e a versão do Android: o técnico
      dessa marca vai precisar de uma orientação de uma vez só (o "Início automático" do Xiaomi, a economia de bateria), a
      preparar antes do piloto. No fim, Sair da conta Admin em cada um.

### O resumo das 7h e das 13h

Depende do servidor e do dia, e do usuário de teste com tarefa, combinado com o PCM. O resumo vai à última conta que abriu
o Nexus Field no aparelho (o servidor guarda cada aparelho numa conta só), e não à conta Admin dos itens de cima.

- [ ] Na véspera, entrar no aparelho de prova com o usuário de teste e deixar assim, sem entrar com outra conta depois.
- [ ] Num dia com tarefa para o usuário de teste, às 7h chega o resumo da manhã (título **Bom dia!**, com as contagens do
      dia) e às 13h o da tarde (título **Boa tarde!**, só com o que falta); o das 13h toma o lugar do das 7h na barra, e o
      que ficar na barra sai sozinho à meia-noite (Android 8 ou mais novo).
- [ ] Num dia sem tarefa para o usuário de teste, nenhum dos dois chega.
- [ ] Com o Nexus Field fechado (tirado dos recentes) e a tela apagada, os dois chegam do mesmo jeito. Se a economia de
      bateria do fabricante segurar o aviso, anotar o modelo e a hora.
- [ ] Sem sinal na hora do envio, o aviso chega quando o sinal voltar (risco aceito na spec), se for no mesmo dia: o
      resumo de um dia que já acabou (o sinal que só volta no dia seguinte) não aparece.
- [ ] (com o cabo) O logcat do app mostra `notificação mostrada (resumo)` ou `(emergencia)` e `atalhos de teste dos avisos
      publicados`, sem título, texto nem número de OS.

### A instalação nova

Num aparelho sem o Nexus Field. Neste, só depois de conferir que nada ficou na fila: desinstalar apaga o espelho.

- [ ] Instalação nova (Android 13 ou mais novo): o **Antes de começar** fala das notificações e, tocando em
      **Continuar**, o Android pede a câmera, a localização e, por último, a notificação.
- [ ] Android 12 ou mais velho (se houver aparelho): nenhum pedido de notificação aparece, e os avisos chegam.

## A localização da equipe (1.2.0, o piloto)

Só depois de as três rotas do Nexus estarem no ar (`/t/campo/equipe/aparelho`, `/posicoes` e `/aparelho/revogar`) e de o
Nexus incluir o aparelho de prova no piloto (a coleta só vale com `coleta: true`). Num aparelho de prova, nunca o de um
técnico, com contas de teste combinadas com o PCM, e de preferência com o mapa da equipe do Nexus aberto em outro lugar,
para ver as posições chegarem. Os textos do termo de uso e privacidade e da notificação são os da prévia (a seção
Localização da equipe de `docs/previas/telas.html`) e valem só depois de aprovados. Nenhuma coordenada, e-mail ou chave vai para este
arquivo: anote o que se viu em palavras.

### Os aparelhos do piloto

O Android muda o pedido da localização e a notificação de versão para versão, e um aparelho só não prova as outras. O
piloto passa este roteiro inteiro em pelo menos um aparelho de cada linha (anote o modelo e a versão, sem dono):

- [ ] Android 10: o pedido do Android logo depois do **Li e aceito** oferece só a localização durante o uso (o nome da
      opção muda com a versão: anote o que apareceu); o pedido do tempo todo, que vem depois, é outra tela do próprio
      Android, com **Permitir o tempo todo**.
- [ ] Android 11 ou mais novo: o pedido do tempo todo leva aos ajustes do Nexus Field (Permissões, Localização), onde se
      escolhe **Permitir o tempo todo**; voltar ao app com o Voltar do Android.
- [ ] Android 9 ou mais velho, se houver um no piloto: depois do **Li e aceito** e da localização, nenhum pedido do tempo
      todo (a localização dada já vale com o app fechado), e a janela da bateria vem logo.
- [ ] Android 11: o pedido oferece também **Apenas esta vez**. Escolhê-la: a coleta do dia liga e segue com o app fechado (o
      serviço já ligado); na abertura do dia seguinte o **Antes de começar** pede a localização de novo, e o termo não
      volta.
- [ ] Android 12 ou mais novo: o pedido oferece a localização **Aproximada** (ver "O técnico sem a permissão de
      localização").
- [ ] Android 13: a notificação do Nexus Field é uma permissão, que se pode negar.
- [ ] Android 14 ou mais novo: a notificação de primeiro plano pode ser arrastada para o lado.
- [ ] De preferência, um Xiaomi, Redmi ou POCO (o que mata o app ao tirar dos recentes), numa instalação nova e com o
      Início automático desligado: a casca o pede depois da bateria (ver "O termo, a janela, a bateria e a notificação
      fixa").

### O termo, a janela, a bateria e a notificação fixa

- [ ] Antes das 6h (hora do celular): abrir o Nexus Field com a conta do piloto: nem o termo nem a coleta aparecem.
      Depois das 6h, abrir de novo: aparece o **Termo de uso e privacidade da localização**, com o texto inteiro (a
      versão, as 12 seções com o título em negrito e a frase final). Ele não fecha tocando fora nem pelo Voltar, e o único
      botão é o **Li e aceito**. Fechar o app sem aceitar: nada é coletado, e a próxima abertura mostra o termo de novo.
- [ ] O **Li e aceito** só no fim: ao abrir, ele está desligado (em cinza), com a dica **Role até o fim para liberar o
      Li e aceito** logo acima, e tocar nele não faz nada. Rolar até o meio do texto: continua desligado. Rolar até o fim
      (a última frase à vista): ele liga e a dica sai. Rolar de volta para cima: continua ligado.
- [ ] Girar a tela com o termo pela metade: o botão continua desligado, e rolar até o fim na outra posição o liga. Ir à
      tela inicial com o termo pela metade e voltar ao Nexus Field: o termo continua aberto, desligado.
- [ ] A fonte grande: em Configurações, Tela, o tamanho da fonte no máximo (1,3x ou mais), e abrir o termo (numa conta
      que ainda não o aceitou): o texto continua legível, o título, a dica e o botão cabem na tela, e rolar até o fim
      liga o botão. Voltar a fonte ao tamanho de antes.
- [ ] A tela pequena (o aparelho de tela menor do piloto, ou o Nexus Field na tela dividida): o botão fica sempre à
      vista embaixo do texto, e o texto rola até o fim.
- [ ] Com o TalkBack, se der: com ele ligado, passar de parágrafo em parágrafo (o texto rola sozinho até o parágrafo)
      ou rolar com dois dedos até o fim: antes do fim o TalkBack diz que o **Li e aceito** está desativado, e no fim ele
      liga. Desligar o TalkBack.
- [ ] A versão nova pede de novo: no aparelho de prova que aceitou o termo curto de antes (o APK de prova anterior a
      esta mudança), instalar o APK novo por cima e abrir com a mesma conta: o termo inteiro aparece de novo, e a coleta
      só liga depois do **Li e aceito**. Depois do aceite, fechar e abrir: ele não volta. Também quem aceitou uma versão
      de antes (a 4, da 1.2.2, ou uma mais velha) vê a versão 5 (10/11/2026, o Início automático do Xiaomi nos ajustes que
      vão ao Nexus e na frase final) de novo, na 1.2.3.
- [ ] Logo depois do **Li e aceito**: o pedido do próprio Android, sem outra tela do Nexus Field antes; escolher
      **Durante o uso do app** (com a localização precisa): a coleta liga. Com a localização já liberada (o **Antes de
      começar** da instalação), nenhum pedido da localização durante o uso aparece: a coleta liga direto.
- [ ] O pedido do tempo todo, uma vez depois da localização (R15): com a coleta já ligando (a notificação fixa
      **Localização da equipe ligada** na barra), vem o pedido do Android da localização o tempo todo, sem outra tela do
      Nexus Field antes (a tela do Android 10 ou os ajustes do 11 em diante, ver "Os aparelhos do piloto"). Escolher
      **Permitir o tempo todo** e voltar ao app: a janela da bateria vem em seguida, e o log da casca diz "o tempo todo
      dado com a coleta ligada: a casca a inicia de novo". Noutra conta de teste (ou depois do **Sair**), ficar com a
      durante o uso: a coleta segue igual, a janela da bateria vem igual, o mapa não muda (não é falta de permissão), e o
      pedido não volta para a mesma conta (fechar e abrir o app, reiniciar o celular, voltar no dia seguinte). Num
      aparelho com o tempo todo já dado, nada se pede.
- [ ] A janela da bateria, uma vez depois do termo: logo depois do **Li e aceito**, da localização dada e da resposta ao
      pedido do tempo todo (a ordem: a localização, o tempo todo, a bateria), com a coleta ligada, aparece a janela do
      próprio Android que pede deixar o Nexus Field sem restrição de bateria (o texto é do Android e muda com a versão e a
      marca: anote o que apareceu), sem outra tela do Nexus Field antes, e a notificação fixa **Localização da equipe
      ligada** já está na barra (a coleta liga antes das duas). Recusar: a coleta segue
      igual, e a janela não volta para a mesma conta (fechar e abrir o app, reiniciar o celular, voltar no dia
      seguinte). Noutra conta de teste (ou depois do **Sair**), o termo e a janela aparecem de novo. Aceitar: em
      Configurações, Bateria, o Nexus Field fica **Sem restrições** (o nome muda com a marca), e a janela não volta. Num
      aparelho com o Nexus Field já liberado antes do termo, nenhuma janela aparece. Se o aparelho não tiver a janela, o
      log da casca diz "a janela da bateria sem restrição não abriu" e nada mais acontece: anote o modelo.
- [ ] A tela do Início automático, uma vez depois da bateria (1.2.3, o caso de 10/11/2026: sem ele, o HyperOS não deixou
      o app partir pelo aviso de app atualizado, e a coleta não voltou). Num Xiaomi, Redmi ou POCO com o Início
      automático desligado, na instalação nova: logo depois da volta da janela da bateria (ou logo depois da resposta ao
      tempo todo, com a bateria já liberada), abre a tela do Início automático do HyperOS, sem outra tela do Nexus Field
      antes (o log da casca diz "a casca abre a tela do Início automático do HyperOS"); se ela não abrir, abrem os
      ajustes do Nexus Field (o log diz "a tela do Início automático do HyperOS não abriu"), onde ele também está: anote
      o modelo e a versão do HyperOS. Ligar o Nexus Field nela e voltar: nada mais aparece. Noutra conta de teste (ou
      depois do **Sair**), desligá-lo antes e recusar: a coleta segue igual, e a tela não volta para a mesma conta (fechar
      e abrir o app, reiniciar o celular). Num aparelho com ele já ligado, ou de outra marca, nenhuma tela.
- [ ] O aviso do que falta (1.2.3): recusar o Permitir o tempo todo, a bateria e o Início automático no fluxo do termo.
      No meio dos pedidos nada aparece; na volta ao app depois da última tela, chega o aviso **Falta liberar no
      celular**, **Falta: Permitir o tempo todo, bateria sem restrição e Início automático. Toque para abrir os ajustes do
      Nexus Field.** (o Início automático só num Xiaomi), no canal **Ajustes da localização**, com o som padrão e sem
      aparecer por cima da tela. Tocar nele abre os ajustes do Nexus Field. Liberar um dos três e voltar ao app: o aviso
      muda a lista sem tocar de novo. Liberar tudo e voltar ao app: o aviso some. Limpá-lo da barra com algo ainda
      faltando: ele volta na próxima volta ao app ou no envio seguinte (uns 2 a 10 min com a coleta ligada), em silêncio
      no mesmo dia e com a mesma lista; com outra lista, ou no dia seguinte, ele toca de novo. Antes das 6h
      e depois das 22h, nada; às 22h ele sai sozinho. Depois do **Sair**, ele some e não volta. Às 6h e depois de
      reiniciar o celular, com algo faltando, ele aparece sem abrir o app (no Xiaomi, só com o Início automático ligado:
      anote se, sem ele, apareceu).
- [ ] O termo é uma vez por conta: fechar e abrir o Nexus Field, reiniciar o celular, voltar no dia seguinte: o termo não
      aparece de novo para a mesma conta.
- [ ] A coleta liga: a notificação fixa **Localização da equipe ligada** fica na barra, sem som e sem vibração.
- [ ] Android 14 ou mais novo: arrastar a notificação fixa para o lado: ela volta na hora, e a coleta segue.
- [ ] A bateria das 6h às 22h: com a coleta ligada, usar o aparelho normalmente por um dia de trabalho (ou o que der de
      tempo) e ver em Configurações, Bateria, o consumo do Nexus Field. Anotar a porcentagem e quanto tempo o aparelho
      passou parado: é o número que o piloto decide se aceita. Parado longe do Wi-Fi (numa usina rural), o ritmo fica
      no de parado (uns 10 min entre posições): o salto da posição de rede não religa o GPS.
- [ ] A parada às 22h: com a coleta ligada, esperar as 22h (ou pôr o relógio do aparelho em 21h59, com o horário
      automático desligado): a notificação fixa sai sozinha, os pontos de até as 22h chegam ao mapa logo depois (o
      último envio), e o Nexus não recebe mais pontos até a primeira abertura do dia seguinte. Voltar o horário
      automático. Às 22h em ponto não vale, e às 6h em ponto vale.
- [ ] O app fechado continua coletando (também só com o **Durante o uso do app**): com a coleta ligada, ir à tela inicial,
      tirar o Nexus Field dos recentes (num aparelho que não o mata) e apagar a tela; andar com o aparelho por alguns
      minutos (ou deixá-lo parado em outro ponto): o mapa mostra posições novas, a cada uns 2 minutos em deslocamento e
      uns 10 parado, e a notificação fixa segue na barra. Com a tela apagada por 30 min ou mais o ritmo se mantém.
- [ ] Com o tempo todo, a coleta do dia começado volta sozinha (R15, o caso de 10/11/2026: o HyperOS e a atualização
      fechavam o app e a coleta parava até a próxima abertura). Com a coleta ligada pela abertura de hoje, sem abrir o
      Nexus Field depois: (1) matar o app (tirá-lo dos recentes no Xiaomi com o Início automático ligado, ou o Android que
      o fecha por falta de memória): em alguns minutos a notificação fixa volta sozinha e o mapa recebe os pontos (o log
      diz "religamento da coleta da equipe pelo sistema: a coleta volta"); (2) reiniciar o celular e desbloquear: a
      notificação fixa volta e os pontos chegam (o log diz "o celular religou: a coleta da equipe volta sozinha"); (3)
      atualizar o Nexus Field por cima (o APK pelo cabo, ou o **Atualizar agora**), sem abri-lo depois: o mesmo (o log
      diz "o Nexus Field foi atualizado: a coleta da equipe volta sozinha"). Do Android 12 em diante, o processo morto
      pode não voltar sem a bateria sem restrição (o log diz "o Android recusou a coleta da equipe em primeiro plano"):
      anote o modelo. O **Forçar parada** é do Android: o app parado à força pode não receber nada até ser aberto; anote o
      que se viu.
- [ ] Sem o tempo todo (recusado no pedido), o Android que mata o app para a coleta até a próxima abertura: no aparelho
      que mata o app ao tirar dos recentes (Xiaomi e afins), ou com **Forçar parada** nos ajustes, a notificação fixa sai
      e o mapa deixa de receber pontos (o aparelho vira "sem sinal" depois de 30 min), e a coleta não volta sozinha, nem
      no mesmo dia, nem com o reinício ou a atualização (o log diz que não volta, "sem a localização o tempo todo"). Abrir o
      Nexus Field: a coleta liga de novo, sem o termo e sem o pedido do tempo todo.
- [ ] Sem abrir o app no dia, nada é coletado (o anti-objetivo da folga e das férias), também com o aviso das 6h na
      barra e com o tempo todo. Com a coleta ligada num dia: (1) reiniciar o celular antes das 6h do dia seguinte; (2)
      noutro dia, reiniciar depois das 22h; (3) noutro dia, atualizar o Nexus Field por cima à noite; (4) noutro dia,
      tirar o app dos recentes no aparelho que o mata. Em cada caso, depois das 6h do dia seguinte e sem tocar no aviso
      nem abrir o Nexus Field até o meio da manhã: a notificação fixa não aparece, e o mapa não recebe nenhum ponto novo
      do aparelho (o log diz que não volta, "o dia não começou"). Abrir o Nexus Field: a coleta liga.
- [ ] A notificação desligada: nos ajustes do Android, desligar o canal **Localização da equipe** (ou as notificações do
      Nexus Field): a coleta para, e o mapa mostra o aparelho sem permissão (o aviso sai no máximo a cada 30 minutos).
      Religar e ver a coleta voltar na próxima volta ao app.
- [ ] Android 13 ou mais novo: negar a notificação do Nexus Field numa instalação nova: a coleta não liga (sem a
      notificação à vista não há coleta), e o mapa mostra o aparelho sem permissão.

- [ ] O Celular pronto (1.2.2): com tudo dado, o Piloto do Nexus mostra a pessoa como **Celular pronto** depois do
      primeiro lote. Tirar o Permitir o tempo todo (deixar só durante o uso) ou pôr a bateria em **Otimizada**, voltar ao
      app: no lote seguinte, o Piloto mostra o que falta (e, num Xiaomi, o Início automático desligado, quando o Nexus
      já ler o `inicioAutomatico` dos ajustes). Quem aceitou a versão 4 do termo vê a versão 5 ao abrir.

### O aviso das 6h

O aviso das 6h (R12) é uma notificação local, sem o Nexus: o alarme é inexato, e com o celular em repouso pode chegar
alguns minutos depois. Cada item pede uma manhã; anote a hora em que o aviso chegou (só a hora, sem nada do aparelho).

- [ ] O aviso chega às 6h ou poucos minutos depois: com a conta do piloto e o termo aceito, usar o Nexus Field num dia
      e não abri-lo depois das 22h. Na manhã seguinte, com o celular parado e a tela apagada desde a noite, às 6h (ou
      poucos minutos depois) aparece o aviso **Bom dia**, **Toque para abrir o Nexus Field e começar o dia.**, no canal
      **Início do dia**, com o som padrão de notificação e sem aparecer por cima da tela. Antes das 6h, nada.
- [ ] O toque abre e liga a coleta: tocar no aviso abre o Nexus Field (sem o termo de novo), a notificação fixa
      **Localização da equipe ligada** aparece e o aviso sai da barra. O mapa recebe as posições do dia.
- [ ] Aberto pelo ícone, sem tocar no aviso: quando a coleta liga, o aviso sai da barra sozinho.
- [ ] A folga: não tocar no aviso nem abrir o Nexus Field: nada é coletado (o mapa não recebe pontos do aparelho), e o
      aviso sai da barra sozinho às 22h.
- [ ] Não chega depois do Sair: à noite, **Sair**; na manhã seguinte, nenhum aviso. Nem num aparelho fora do piloto (a
      conta que o Nexus não escolheu, ou a conta Admin), nem numa conta que ainda não aceitou o termo.
- [ ] Reiniciar o celular não perde o aviso: à noite, reiniciar o celular e não abrir o Nexus Field: o aviso chega às 6h.
      Também depois de atualizar o Nexus Field por cima (o APK novo instalado à noite, sem abrir). No Xiaomi e afins, só
      com o Início automático ligado (a casca o pede depois do termo): anote se, sem ele, o aviso chegou.
- [ ] O balde de espera restrito: com o app num balde de espera restrito (a economia de bateria agressiva), o Android
      pode atrasar o aviso das 6h: é limite do sistema sem alarme exato, e não defeito. Anote a hora em que chegou.
- [ ] O canal desligado não mostra: nos ajustes do Android, desligar o canal **Início do dia**: na manhã seguinte nada
      aparece, e a coleta liga quando o Nexus Field for aberto. Religar o canal: o aviso volta na manhã seguinte.
- [ ] O que apaga o alarme: com **Forçar parada** do Nexus Field à noite, o Android apaga os alarmes dele, e o aviso da
      manhã seguinte não chega; a próxima abertura o refaz (o aviso volta no outro dia). No aparelho que mata o app ao
      tirar dos recentes (Xiaomi e afins), tirar o Nexus Field dos recentes à noite e anotar se o aviso chegou.

### O técnico sem a permissão de localização e o Sair

- [ ] Negar no pedido do Android logo depois do **Li e aceito** (**Não permitir**): o Nexus Field funciona como antes,
      sem coletar, nenhuma outra tela do Nexus Field aparece, e o mapa mostra "sem permissão de localização" para o
      aparelho (o aviso sai no máximo a cada 30 minutos). Voltar ao app não pede de novo; a próxima abertura pede a
      localização pelo **Antes de começar**, e o termo não volta.
- [ ] Negada de vez (Android 11 ou mais novo, duas recusas: o Android não mostra mais o pedido): o **Li e aceito** (de
      outra conta, ou depois do Sair) não mostra nada além do termo, e o mapa mostra o aparelho sem permissão. Liberar
      pelo caminho do guia de instalação (**Permissões**, **Localização**, **Permitir o tempo todo** ou a durante o uso do
      app, com **Usar localização precisa** ligado; o nome da opção muda com a versão) e voltar ao app: a coleta liga.
- [ ] Android 12 ou mais novo, a **Aproximada**: no pedido logo depois do **Li e aceito**, escolher **Aproximada**: a
      coleta não liga e o mapa mostra o aparelho sem permissão. Escolhida no pedido do **Antes de começar**, aparece
      também o aviso **Localização precisa desligada**. Ligar **Usar localização precisa** nos ajustes e voltar ao app: a
      coleta liga.
- [ ] A permissão tirada com a coleta ligada: nos ajustes do Android, trocar a localização para **Não permitir** (ou
      desligar **Usar localização precisa**; o Android costuma matar o processo nessa hora). Sem abrir o app: a
      notificação fixa sai (na hora ou na próxima posição) e não volta, e o mapa para de receber pontos. Abrir o app: o
      **Antes de começar** pede a localização de novo, e o termo não volta.
- [ ] Só o tempo todo tirado com a coleta ligada (nos ajustes, trocar **Permitir o tempo todo** pela durante o uso; o
      Android costuma matar o processo nessa hora): a coleta não volta sozinha (o log diz "sem a localização o tempo
      todo"), e a próxima abertura a liga com a durante o uso, sem pedir o tempo todo de novo.
- [ ] O Sair que revoga: com a coleta ligada, **Sair**: a notificação fixa some na hora, a coleta para e o mapa deixa de
      receber pontos; o aparelho não recebe mais nada em nome da conta de antes. Entrar de novo na mesma conta: o termo
      aparece de novo (o Sair esquece a conta, a chave e o aceite).
- [ ] O Sair sem rede: com a coleta ligada, modo avião, **Sair**, tirar o modo avião sem entrar: a notificação fixa já
      saiu e nenhum ponto sobe. Entrar com a conta do piloto: o termo aparece de novo.
- [ ] Um aparelho fora do piloto (conta de teste que o Nexus não escolheu, ou a conta Admin): nem o termo nem a coleta
      aparecem, e a 1.2.0 se comporta como a 1.1.1.

### O Nexus que muda de ideia com a coleta ligada

- [ ] O 401 no meio do dia: com a coleta ligada e o app aberto, pedir ao chat do Nexus para revogar a chave do aparelho.
      No envio seguinte (até uns 2 minutos em deslocamento, 10 parado) a coleta para sozinha e a notificação fixa sai.
      Ir à tela inicial e voltar ao Nexus Field passados 10 minutos do último pedido da página: a página pede a chave
      nova sozinha (sem recarregar), e a coleta liga de novo sem tela nenhuma (o termo é da conta, e a conta é a mesma).
      Os pontos que estavam na fila sobem com a chave nova. As voltas seguintes não pedem outra chave.
- [ ] O `coleta:false` no meio do dia: com a coleta ligada, pedir ao chat do Nexus para tirar o aparelho do piloto. No
      envio seguinte a coleta para, a notificação sai, e a fila é apagada. A volta ao app não religa a coleta (a página
      pergunta ao Nexus uma vez e ele responde que a coleta não vale), nem a abertura seguinte.
- [ ] O 401 com a fila cheia: com a chave revogada no Nexus e pontos na fila, abrir o Nexus Field com a mesma conta: a
      chave nova entra e a fila antiga sobe com ela; com outra conta, a fila não sobe.

### A troca de conta e a abertura sem sinal

- [ ] A troca de conta (as duas contas de teste no mesmo celular): com a conta A coletando, entrar com a B **sem** o Sair
      da A (o login da página trocando de conta). Nada sai em nome da A depois disso, a fila da A não sobe com a chave da
      B, e a B vê o termo (o **Li e aceito** liga a coleta). Voltar ao A pelo mesmo caminho: o mesmo, com o termo de novo.
- [ ] Outra conta na página depois da partida nova (R14): com a conta A coletando, **Forçar parada** do Nexus Field,
      abri-lo e apagar a tela logo em seguida (a página não carrega) e deixar uns minutos: os pontos vão em nome da A (o
      custo que o dono aceitou). Desbloquear e entrar com a conta B na página, sem o Sair da A: a troca de conta (a coleta
      para, a fila e a chave da A saem, a chave da A é revogada), a B vê o termo, e nada mais sai em nome da A, nem depois
      de outra partida.
- [ ] A abertura em modo avião: com o aparelho em modo avião, abrir o Nexus Field (a chave já estava guardada, e a conta
      já conferida num dia de antes): a coleta liga e guarda os pontos na fila. Tirar o modo avião: os pontos sobem no
      envio seguinte, sem esperar a página (o log da casca diz uma vez "a conta da chave da equipe conferida numa partida
      de antes"). Só a chave guardada pela 1.2.0, sem a conferência guardada, espera a página conferir na primeira
      abertura da versão nova (o log "a conta da chave da equipe conferida nesta partida"); a fila guarda 24 h e no
      máximo 1000 pontos.
- [ ] A nova tentativa ao voltar o sinal: abrir o Nexus Field em modo avião com o aparelho sem chave, tirar o modo avião
      e esperar: a página pede a chave sozinha quando a rede volta (ou na volta ao app), no máximo a cada 10 minutos, e a
      coleta liga (com o termo, se a conta ainda não o aceitou).
- [ ] A conta conferida guardada (R14, o caso de 10/11/2026: depois da atualização, os pontos só subiam quando o app
      vinha à tela): com a coleta ligada e a conta já conferida, (1) atualizar o Nexus Field por cima; (2) noutra hora,
      **Forçar parada**; (3) noutra hora, reiniciar o celular. Em cada caso, abrir o Nexus Field e apagar a tela antes de
      a página carregar (ou abri-lo com a tela apagada, como a instalação pelo cabo faz), sem desbloquear: a coleta liga,
      e os pontos chegam ao mapa com a tela apagada (uns 2 minutos em deslocamento), sem esperar o app à vista (o log diz
      "conferida numa partida de antes: o envio sai sem esperar a página"). Desbloquear: a página confere a mesma conta,
      e nada muda.

### Os avisos `equipe` do log

Só se lê o log do Nexus Field (o logcat do aparelho de prova, filtrado ao app) e só o texto: coordenadas, chaves, e-mails
e ids de aparelho nunca aparecem, e se aparecerem é defeito. Cada aviso que a página manda chega como
`equipe: erro da página, motivo <motivo> (N nesta partida)`, e o motivo diz o que foi (o texto do aviso não vai ao log):

- [ ] `semId`: o token da página não diz de quem é a conta (sem `oid` nem `sub`; é do site). A chave da equipe não é
      pedida e o aparelho não entra na coleta; uma vez por carga.
- [ ] `semResumo`: a página sem `crypto.subtle` (WebView antiga), ou o resumo da conta que falhou ou saiu fora do
      formato. Mesmo efeito, uma vez por carga.
- [ ] `http401`: o Nexus recusou o token (vencido, ou o público ou o tenant que o Nexus confere não batem com os do App
      de Campo). `http404`: a rota do registro não está no ar. `http5xx`: a Microsoft fora ou o Nexus sem configuração.
      `httpOutro`: outro status.
- [ ] `rede`: sem rede, o tempo esgotado (o registro que não respondeu em 10 minutos) ou o CORS. Enquanto o Nexus
      responder o OPTIONS da rota com o redirecionamento ao login (medido em 08/11/2026), cada carga com o técnico logado
      manda um `rede` (o TypeError do fetch), e não um `http404`: é esperado até o chat do Nexus pôr a rota no ar.
- [ ] `contrato`: o 200 do Nexus fora do contrato (sem a chave, sem o `coleta`, que não se lê). `nativo`: o nativo
      respondeu sem o id do aparelho. `canal`: a mensagem ao nativo não saiu.
- [ ] Cada falha do nativo ou do Nexus avisa uma vez por tentativa, e a página tenta de novo no online e na volta ao
      app, a cada 10 minutos no máximo.
- [ ] O Bearer que não vem (sem login) não avisa: é a conta ainda sem sessão, e a nova tentativa vem no login.

## O que não se vê no aparelho sem ferramenta

- O Android 7, que não tem canais de notificação: lá a própria notificação faz o papel do canal (na emergência, o alarme
  e as três vibrações; no resumo, o som padrão; nada na silenciosa). Só num aparelho com Android 7; a prova é o
  `NotificacoesNoAndroid7Test`.
- O toque na emergência com o processo do Nexus Field morto pelo Android e a tarefa ainda nos recentes (o caso comum num
  aparelho com pouca memória): provocá-lo pede o shell do adb, que o aparelho de prova não permite. A prova é o
  `MainActivityNaTelaTest`: a tela que volta com estado salvo não lê o Intent antigo, e o toque novo chega pelo
  `onNewIntent`.
- A faxina das fotos da reserva da câmera (as de mais de 48 h saem da pasta do app na abertura): a pasta não aparece sem
  o cabo, e o teste de JVM (`FaxinaDaCameraTest`) é a prova.
- O teto das recriações (o renderer que morre 4 vezes em 60 s mostra a tela "Sem internet" em vez de piscar): não há
  como provocar sem ferramenta; o teste de JVM (`TetoDeRecriacaoTest`) e a montagem são a prova.
- A WebView antiga (sem os recursos de que a casca precisa): só num aparelho com a WebView desatualizada.
- Para quem programa, com o cabo (só leitura): o estado do Início automático do Nexus Field no Xiaomi sai em
  `adb shell appops get br.com.gridco.nexusfield 10008` (`allow` ligado, `ignore` desligado).
