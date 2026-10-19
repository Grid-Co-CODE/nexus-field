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
Fracttal, e a emergência traz sempre a mesma OS, a `12345` (no toque, o que se confere é o número na busca da aba
Tarefas; o que a busca encontra para ele não faz parte desta prova). As telas e os textos de cada aviso estão na prévia
(`docs/previas/telas.html`, seção Notificações). Siga as subseções na ordem: num aparelho só, a atualização e a permissão
vêm primeiro, e cada subseção deixa o aparelho pronto para a seguinte.

### Antes de tocar em qualquer aviso

- [ ] O aparelho de prova tem a Release 1.0.0 (instalada pelo link) com a câmera e a localização PRECISA já liberadas: em
      Configurações > Apps > Nexus Field > Permissões, a Câmera permitida e a Localização permitida com "Usar localização
      precisa" ligado. Com a aproximada, a 1.1.0 abre com o **Antes de começar** (que pede tudo de novo) no lugar do
      **Avisos do Nexus Field**, e a prova da atualização não acontece.
- [ ] As rotas do servidor estão no ar (v286 ou mais nova): `/me`, `/push/registrar`, `/push/esquecer` e `/push/teste`.
      Conferir com a sessão "App de campo".
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
      **Permitir a atualização**, se for a primeira vez). Atualizar e abrir.
- [ ] Na primeira abertura da 1.1.0 (Android 13 ou mais novo) aparece **Avisos do Nexus Field**, uma vez só; tocar fora
      e o Voltar não o fecham, e **Continuar** leva ao pedido do Android. (No Android 12 ou mais velho não há aviso nem
      pedido, e as notificações já vêm ligadas: passe ao item dos ajustes, mais abaixo.)
- [ ] No pedido do Android, negar ("Não permitir").
- [ ] Tirar o Nexus Field dos recentes e abrir de novo: nada é perguntado, nem o aviso nem o pedido.
- [ ] Entrar com a conta Admin (pelo "Entrar" da página, se ela pedir). Em alguns segundos, o toque longo no ícone mostra
      **Testar avisos** e **Testar avisos fora do expediente**. **Testar avisos**: o aviso rápido diz **Aviso de teste
      enviado para 0 aparelho(s)** (ou o número de outros aparelhos em que a conta Admin entrou), e nada aparece neste:
      com a notificação negada, o Nexus Field nem se registra no servidor.
- [ ] Ligar a notificação em Configurações > Apps > Nexus Field > Notificações e voltar ao Nexus Field: ele se registra
      sozinho em alguns segundos. **Testar avisos** de novo: **Aviso de teste enviado para 1 aparelho(s)**, e o resumo e a
      emergência de teste chegam. Nos mesmos ajustes estão os três canais, **Resumo do dia**, **Emergências** e
      **Emergências fora do expediente**.
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
      som padrão), e a emergência (título **Emergência**, OS 12345) no canal **Emergências**: aparece por cima da tela,
      toca o alarme do aparelho e vibra três vezes. Fora desse horário ela chega em silêncio (ver "A emergência fora do
      expediente", abaixo).
- [ ] **Testar avisos fora do expediente**: a emergência de teste chega no canal **Emergências fora do expediente**, na
      barra, sem som, sem vibração e sem aparecer por cima da tela. O resumo de teste que vem junto TOCA, no **Resumo do
      dia**: o "sem som" é só da emergência.
- [ ] Dois toques seguidos num atalho, em menos de 10 s: o segundo mostra **Teste já pedido. Espere alguns segundos para
      pedir outro**, e chega um par de avisos só.
- [ ] Com a rede cortada, o toque no atalho mostra **Não consegui enviar o teste agora** (em até 20 s).
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

Use **Testar avisos** no expediente, com a barra limpa antes de cada item. A regra é que o toque nunca interrompe o
técnico: ocupado, a OS fica pendente e vai à busca sozinha quando ele ficar livre, e a emergência fica na barra até a OS
ser mostrada.

- [ ] No fundo, na aba Tarefas, sem nada aberto: tocar abre já, com `12345` na busca, e a emergência sai da barra.
- [ ] No fundo, em outra aba sem nada aberto (a lista da Zeladoria, por exemplo): tocar troca para a aba Tarefas e põe
      `12345` na busca.
- [ ] Na aba Ronda, na primeira etapa, sem escolher a usina nem responder nada (a ronda não começou): tocar troca para a
      aba Tarefas e põe `12345` na busca.
- [ ] Na aba Ronda, com a usina escolhida ou uma resposta dada (a ronda começou): tocar traz o Nexus Field para a frente
      sem sair da Ronda, a OS fica pendente e a emergência continua na barra. Ao sair da Ronda (para outra aba) ou ao
      finalizá-la, em até uns 2 s a aba Tarefas abre com `12345` na busca.
- [ ] Com uma OS aberta (preenchendo): tocar traz o Nexus Field para a frente sem fechar a OS nem trocar de aba, e a OS
      fica pendente. Ao fechar a OS, em até uns 2 s a aba Tarefas abre com `12345` na busca.
- [ ] Com um serviço da Zeladoria aberto (preenchendo): igual, a OS fica pendente até ele voltar à lista.
- [ ] Com a câmera interna, uma folha ou a APR abertas por cima da OS: o toque não fecha nenhuma delas, e a OS fica
      pendente até ele fechar tudo.
- [ ] Nexus Field fechado (tirado dos recentes): tocar abre o Nexus Field na tela de entrada do App de Campo, e a OS
      espera. Tocar em "Entrar": depois da entrada, a aba Tarefas abre com `12345` na busca, e a emergência sai da barra.
- [ ] Com uma OS pendente, tirar o Nexus Field dos recentes e abrir pelo ícone: a OS não abre sozinha (a pendência era
      daquela partida), e a emergência continua na barra; tocar nela de novo leva à OS (depois do "Entrar"), e aí ela sai
      da barra.
- [ ] Com a câmera do sistema aberta pela reserva (a foto pela reserva do `<input capture>`, de "O roteiro da spec"),
      tocar na emergência: a câmera fecha e a foto que estava sendo tirada se perde. É limite conhecido (o Android fecha o
      que está por cima do Nexus Field quando o traz para a frente): a foto se tira de novo. Anotar se foi assim.
- [ ] A busca ao vivo com uma OS que existe: o PCM atribui a OS de teste ao usuário de teste DEPOIS de o Nexus Field abrir
      com ele (para ela não estar na lista do dia); digitar o número dela na busca da aba Tarefas, que é o que o toque
      faz: o cartão dela aparece, trazido do Fracttal.
- [ ] Tocar no resumo abre o Nexus Field onde ele estava, sem mexer na tela, e o resumo sai da barra.

### O app fechado, o Sair e quem recebe (com o segundo aparelho)

Com o segundo aparelho na mesma conta Admin, ou com o envio de teste pedido à sessão "App de campo". Com a conta Admin
nos dois aparelhos, o aviso rápido do teste conta os dois.

- [ ] A emergência com o Nexus Field fechado: no aparelho de prova, tirar o Nexus Field dos recentes e apagar a tela; no
      outro, **Testar avisos** (no expediente): a emergência chega ao de prova, por cima da tela e tocando.
- [ ] O esquecer do Sair: no aparelho de prova, com internet, Sair (os avisos do Nexus Field saem da barra dele); no
      outro, **Testar avisos**: o aviso rápido conta um aparelho a menos, e nada chega ao de prova.
- [ ] O Sair sem rede: entrar de novo no de prova com a conta Admin, pôr o modo avião, Sair e tirar o modo avião sem
      entrar com conta nenhuma; no outro, **Testar avisos**: nada aparece no de prova (o aviso rápido pode ainda contá-lo:
      o servidor não soube do Sair, mas o Nexus Field fica calado até alguém entrar). Entrar de novo no de prova: os
      avisos voltam.
- [ ] As marcas da frota: num aparelho de cada marca (Xiaomi ou Redmi, Samsung e Motorola), com a conta Admin, tirar o
      Nexus Field dos recentes e apagar a tela por 30 min ou mais; de outro aparelho, **Testar avisos**: o aviso chega. Se
      não chegar, anotar a marca, o modelo e a versão do Android: o técnico dessa marca vai precisar de uma orientação de
      uma vez só (o "Início automático" do Xiaomi, a economia de bateria), a preparar antes do piloto.

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
- [ ] Sem sinal na hora do envio, o aviso chega quando o sinal voltar (risco aceito na spec).
- [ ] (com o cabo) O logcat do app mostra `notificação mostrada (resumo)` ou `(emergencia)` e `atalhos de teste dos avisos
      publicados`, sem título, texto nem número de OS.

### A instalação nova

Num aparelho sem o Nexus Field. Neste, só depois de conferir que nada ficou na fila: desinstalar apaga o espelho.

- [ ] Instalação nova (Android 13 ou mais novo): o **Antes de começar** fala das notificações e, tocando em
      **Continuar**, o Android pede a câmera, a localização e, por último, a notificação.
- [ ] Android 12 ou mais velho (se houver aparelho): nenhum pedido de notificação aparece, e os avisos chegam.

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
