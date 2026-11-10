# Mapa de equipe: a localização dos técnicos pelo Nexus Field (desenho)

08/11/2026. Pedido do dono da casca e do Nexus. A ideia estava na fila dele desde 21/10/2026, com decisões tomadas
naquele dia (marcadas "21/10"). Leia antes o `CLAUDE.md` deste repositório; para a parte do Nexus, o `CLAUDE.md` da raiz
do Nexus, o `nexus/torres/campo/CLAUDE.md` (torre Usinas 360°) e o `nexus/dados/CLAUDE.md` (PRIORIDADE 0).

## 1. Intenção

Ver onde está cada técnico que tem o Nexus Field e abrir a rota dele até uma usina. Nas palavras do dono: "identificar
onde o técnico está e qual seria a melhor rota para ele, claro que vamos avisar que estamos pegando esses dados e será
tudo respeitado, mas essa informação é essencial." Em 21/10 ele disse que serve "para ver onde o técnico está e traçar
rotas melhores entre usinas", e que só sai dentro da LGPD ("será só com LGPD mesmo").

A coleta é da casca Android (este repositório). O dado vai para o Nexus, e a tela é o **Mapa de equipe**, na torre
**Usinas 360°**. A parte do Nexus é do chat "Nexus (Usinas 360)" (o dono, 08/11: "caso dê certo mande msg para Nexus
(Usinas 360)").

## 2. Objetivos, não-objetivos e anti-objetivos

**Objetivos**
- A posição de cada técnico de campo com o Nexus Field, da primeira abertura do app no dia até as 22h, também com o app
  fechado depois disso.
- No Nexus, o Mapa de equipe com a última posição de cada técnico, a idade dela e a situação, e o **Abrir rota** no
  Google Maps do técnico até a usina escolhida, e o percurso de um técnico num período dos últimos 7 dias, sob demanda
  (R13, 10/11/2026).
- O histórico curto dos pontos e o resumo dos deslocamentos entre usinas, para melhorar as rotas depois.

**Não-objetivos**
- iPhone: continua no App pelo navegador, sem coleta.
- Ordenar os técnicos pela distância até uma usina, ou planejar a rota do dia entre várias usinas (o dono escolheu só o
  Abrir rota).
- Coleta antes da primeira abertura do dia, antes das 6h ou depois das 22h.
- O técnico ver a posição dos colegas.
- Mudança no App de Campo (o site e o servidor dele) e no Fracttal.

**Anti-objetivos (é fracasso mesmo funcionando)**
- Coletar sem o técnico saber: sem o termo de uso aceito antes de ligar, ou sem a notificação fixa enquanto coleta.
- Coletar em férias ou folga (sem o técnico ter aberto o app no dia), antes das 6h ou depois das 22h.
- (Decisão do dono em 10/11/2026, R13: o mapa passou a mostrar o ponto exato também parado fora de usina, e o percurso
  de um período; "mostrar onde o técnico mora" deixou de ser anti-objetivo, e o termo da versão 2 diz isso ao técnico.)
- A coordenada (da usina ou do técnico) em texto na página ou no JSON do Nexus, ou em claro no banco.
- Ponto bruto usado ou mostrado depois de 7 dias.
- Bateria que acaba no meio do dia e leva o técnico a desinstalar o app.

## 3. Decisões do dono

- **Quando liga:** na primeira abertura do Nexus Field no dia, entre 6h e 22h, e vai até as 22h (08/11). Em férias ou
  folga, sem abrir o app, nada é coletado. Antes disso ele tinha dito "das 06:00 às 22:00", por causa da hora extra. Às
  6h, um aviso no celular chama essa abertura (10/11, R12, depois do piloto em que a coleta só ligou às 07:46: "Só avisa,
  mas tem que coletar cara!"); ligar sozinho às 6h foi recusado, porque pediria a localização "o tempo todo" e coletaria
  em folga.
- **Sem pausa:** "não pode" (08/11). Ligada, a coleta não tem botão de parar até as 22h.
- **Quem vê:** os setores PCM, COS, Performance, Supervisão Campo e Responsáveis UFV, todos o mapa inteiro (08/11).
- **Rota:** "Abrir a rota no Google Maps ou Waze", do ponto do técnico até a usina escolhida (08/11; ver a seção 9 sobre
  o Waze).
- **Guarda:** 7 dias os pontos brutos; 1 ano o resumo de cada deslocamento entre usinas (de onde, para onde, saída,
  chegada, duração) (08/11).
- **Como se apaga:** a T.I. apaga da API (08/11). Os pontos vão ao banco pela API como todo dado do Nexus, e a T.I. faz a
  limpeza periódica dos pontos com mais de 7 dias e dos deslocamentos com mais de 1 ano.
- **Frequência (21/10):** a cada 2 ou 5 minutos, "a depender de quanto vai utilizar".
- **Condições (21/10):** aviso ao técnico, regra escrita, cifrado, acesso restrito e guarda curta. Não são opção.
- **O dado vai ao Nexus**, na torre Usinas 360° (08/11).

## 4. Como funciona

### 4.1 No celular (Nexus Field 1.2.0, este repositório)

- **Quem é coletado.** O Nexus decide, ao dar a chave do aparelho (abaixo): só técnico de campo ativo do cadastro, e no
  piloto só os técnicos escolhidos. Para os outros (supervisor, administrador, o aparelho de prova, quem não está no
  piloto) a casca não mostra o termo nem coleta. Assim a 1.2.0 pode ir a todos os aparelhos com a coleta desligada para
  quem não entrou.
- **O termo de uso e privacidade antes de ligar (R10, 08/11; R11, 09/11).** A única tela do Nexus Field para a coleta:
  o "Termo de uso e privacidade da localização", uma vez por conta (outra conta no mesmo celular o vê de novo; a chave
  refeita da mesma conta, não) e por versão do texto, com um botão só, "Li e aceito" (sem o aceite nada coleta, e a
  próxima abertura mostra o termo de novo). É o aviso de privacidade inteiro, pela LGPD, em 12 seções com o título em
  negrito: quem é o responsável (a Grid Co.), quais dados (a posição, a precisão, a velocidade, o deslocamento, a hora e
  o fuso, o código do aparelho e a conta; nada além disso), quando (das 6h às 22h, a partir da primeira abertura do dia,
  também com o app fechado, sempre com a notificação fixa), para quê (ver onde a equipe de campo está, abrir a rota até
  as usinas e planejar as rotas), a base legal (o legítimo interesse e a execução do contrato de trabalho, art. 7º, V e
  IX), quem vê (PCM, COS, Performance, Supervisão Campo, Responsáveis UFV e os administradores do Nexus; parado fora de
  usina, a última posição de cada técnico nos últimos 7 dias, no ponto exato, e o percurso de um período; cada abertura do
  mapa, do percurso ou da rota registrada, e a do percurso com o período; texto da versão 2, 10/11/2026, R13), com quem é compartilhado (ninguém de
  fora; o Google Play services calcula a posição e o Google Maps traça a rota aberta), por quanto tempo (7 dias os
  pontos, 1 ano os deslocamentos, o registro de quem abriu o mapa para responder pela LGPD), a segurança (cifrado no
  caminho e no Nexus, 24 h no máximo no celular, a chave que o Sair apaga), os direitos (art. 18, a ANPD, os pedidos pelo
  RH), como parar (às 22h, o Sair, a permissão tirada) e que o texto novo pede um aceite novo; no fim, o que escolher no
  pedido do Android que vem em seguida (Durante o uso do app). O texto rola dentro do diálogo e o "Li e aceito", sempre à
  vista embaixo, só liga depois de o fim do texto aparecer na tela (com uma dica logo acima enquanto isso; o texto que
  cabe inteiro libera na hora; o TalkBack e o teclado rolam igual). O aceite guarda a versão do texto (`TERMO_VERSAO`,
  "1", o de 09/11/2026): o texto que mudar muda a versão, e quem aceitou a de antes vê o termo de novo. Os textos vão à
  prévia (`docs/previas/telas.html`), ao RH e ao jurídico (do jurídico confirmar: a base legal e o RH como canal dos
  direitos), e só valem depois de aprovados. Casos que criaram a regra: o dono viu a prévia com o aviso e as duas
  explicações da localização "o tempo todo" e pediu "só cria um termo de uso para eles darem ok, essa quantidade de
  telas e avisos atrapalham muito a experiência" (R10); ao aprovar os textos, pediu "Quero que o termo de notificação
  inclua tudo referente a LGPD e seja que nem as empresas grandes fazem, aparece a pessoa tem que rolar até o final para
  aceitar" (R11). Fica para depois (muda o contrato da seção 8): mandar ao Nexus a prova do aceite (a versão e a hora).
- **A permissão.** A localização durante o uso, a precisa (a aproximada erra por quilômetros, e o ponto no mapa e o
  Abrir rota precisam dele): logo depois do aceite, o pedido do próprio Android (a precisa e a aproximada juntas), sem
  tela nossa antes; fora do aceite, quem a pede é o "Antes de começar" da casca, a cada abertura. A "o tempo todo" não é
  pedida: o serviço do tipo localização iniciado com o app à vista mantém a localização durante o uso enquanto roda,
  também com o app fechado. Por isso nenhum caminho inicia o serviço com o app no fundo (iniciado assim, ele não teria a
  localização do Android 11 em diante, e do 12 em diante o Android recusa o primeiro plano): só a tela à vista o liga, e
  o processo que o Android mata não o religa. Negada, ou negada de vez (o Android não mostra mais o pedido): nenhuma
  tela nossa a mais; o app funciona como hoje, o mapa mostra esse técnico como "sem permissão de localização", e o guia
  de instalação diz como liberar nos ajustes. O serviço confere a permissão ao ligar, a cada posição e a cada envio:
  tirada nos ajustes com a coleta ligada, a coleta para e não volta sem o técnico abrir o app.
- **A coleta.** Liga na primeira abertura do app no dia (entre 6h e 22h, na hora do celular), com um serviço em primeiro
  plano do tipo localização e uma notificação fixa (por exemplo, "Nexus Field: localização da equipe ligada até as
  22h"). Segue com o app fechado enquanto o serviço roda; o Android que mata o app (a falta de memória, o "tirar dos
  recentes" de quem mata o app) para a coleta até a próxima abertura. Às 22h para sozinha, a notificação sai e o que
  ficou na fila sai num último envio. Envio a cada 2 minutos em deslocamento e mais espaçado parado (o salto da posição
  de rede com o celular parado, dentro da precisão das leituras, não conta como deslocamento). Sem sinal, os pontos
  esperam no celular e saem quando o sinal voltar. Sem a notificação fixa à vista, a coleta não liga, e o mapa mostra o
  técnico como sem permissão.
- **O aviso das 6h (R12, 10/11).** Todo dia às 6h na hora do celular (com o celular em repouso, pode chegar alguns
  minutos depois: o alarme é inexato, sem a permissão de alarme exato), a notificação "Bom dia", "Toque para abrir o
  Nexus Field e começar o dia.", num canal próprio ("Início do dia", com o som padrão). O toque abre o Nexus Field, e a
  abertura liga a coleta pelo caminho de sempre; o aviso sozinho não coleta nada, e quem está de folga o ignora. Só
  aparece com a chave do aparelho e o termo da versão atual aceito, e com a coleta ainda parada; decide na hora do
  alarme, então o Sair, o `coleta: false` e o 401 (que apagam a chave e cancelam o alarme) não deixam nada aparecer. Sai
  da barra no toque, quando a coleta liga ou às 22h. O alarme volta depois de reiniciar o celular, de atualizar o app e
  de mudar o fuso, e cada abertura do app o refaz (o "Forçar parada" apaga os alarmes do app). Com a notificação negada
  ou o canal desligado, nada aparece.
- **O que a casca faz com as respostas do Nexus.** O 200 só vale com o `coleta` booleano: o `true` tira os pontos da
  fila, o `false` para; sem o campo, nulo ou de outro tipo, é como sem rede (nada sai da fila nem se apaga). O 4xx que
  não é o 401 é o lote recusado, que sai da fila para não travar os seguintes; o 403, o 404, o 405 (o acesso ou a rota,
  e não o lote), o 408 e o 429 esperam como sem rede. O ponto da fila que não se lê sai antes do lote.
- **A identidade do aparelho.** Com o app fechado não há o login da página para mandar as posições. Na carga com o
  técnico logado, a casca pede ao Nexus uma chave própria do aparelho, mostrando o login Microsoft da página; o Nexus
  confere esse login, sabe quem é a pessoa, diz se a coleta vale para ela e devolve a chave. O token Microsoft não sai da
  página. A casca guarda a chave cifrada (como a identidade do espelho) e a usa em cada envio. O Sair apaga a chave no
  celular e pede ao Nexus para revogá-la.
- **A chave é da conta.** A página calcula a conta (o SHA-256 de uma frase fixa mais o `oid`, ou o `sub`, do token
  Microsoft) e a manda ao nativo junto com o pedido e com a chave; o hash não sai do aparelho. O nativo guarda a conta com
  a chave e só diz que há chave quando é a da mesma conta. Outra conta no mesmo celular, sem o Sair da anterior, faz o
  trabalho do Sair da anterior (para a coleta, apaga a fila, revoga e apaga a chave, esquece o termo aceito) e pede o termo
  de novo: a conta B nunca herda a chave, o termo aceito nem a fila da A. A conta guardada é a dona da fila e do termo
  aceito e sobrevive ao 401 (que apaga só a chave): a mesma conta sobe a fila com a chave nova, sem ver o termo de novo, e
  outra conta não a herda. O Sair, a troca de conta e o `coleta:false` apagam tudo (o próximo login vê o termo de novo).
  Uma conta ilegível (Keystore invalidado, backup restaurado) conta como fila sem dona, e a fila é apagada.
- **O envio espera a conta conferida.** A coleta liga com a chave guardada e guarda os pontos, mas nada sai (nem o aviso
  de que falta a permissão) até a página da tela que abriu conferir a conta: o pedido do aparelho da mesma conta, ou a
  chave nova guardada. O custo: o app aberto sem token (sem sinal, sessão vencida) coleta e só envia depois da
  conferência; a fila guarda 24 h e no máximo 1000 pontos. A revogação do Sair não espera. A conferência fica só na
  memória (o serviço não volta sem a tela, e nenhum processo sem página precisa dela), e a tela que abre a desfaz até a
  página dela conferir. O cofre do aparelho que falha na conferência não deixa o dia parado: o envio e a volta ao app
  conferem de novo quando ele voltar.
- **A nova tentativa da página.** O que não terminou (o token ainda sem login, a falha do nativo ou do Nexus) tenta de novo
  no online e na volta ao app, no máximo a cada 10 min contados do pedido ao nativo; com o pedido no ar nada começa outro
  (o token ou o POST que não respondem em 10 min contam como falha), e depois do Sair nada. Terminou: há chave
  (`temChave`), ou a chave ou o `coleta:false` foi entregue ao nativo. Depois de terminar com a chave, a volta ao app
  pergunta de novo ao nativo se ela ainda está lá (o 401 do envio a apaga), no máximo a cada 10 min, e só então vai ao
  Nexus. As falhas que só aparecem no aparelho (o token sem id, a página sem `crypto.subtle`) e as do nativo e do Nexus
  saem como aviso `equipe` no log, com um motivo de lista fechada (o token sem id, o 401, a rede ou o CORS...) e sem
  coordenada nem chave.
- **Xiaomi e afins.** A coleta com o app fechado depende do "Início automático" e da bateria sem restrições, que o guia
  de instalação já manda ligar para os avisos (`docs/instalacao/`). O guia ganha a linha da localização.

### 4.2 No Nexus (chat "Nexus (Usinas 360)")

- **Três rotas novas.** Registrar o aparelho (confere o login Microsoft, diz se a coleta vale para a pessoa e devolve a
  chave), receber as posições (com a chave) e revogar a chave. As que a casca chama sem a sessão do Nexus entram nas
  `ROTAS_PUBLICAS`, conscientemente, e só aceitam a chave ou o login conferido.
- **O dado (PRIORIDADE 0).** Entra primeiro no catálogo, com o grão escrito:
  - o ponto: uma posição de um técnico num instante, com `pessoa_id`, a hora e a coordenada **cifrada**;
  - o deslocamento: uma ida de uma usina a outra (usina de saída, usina de chegada, saída, chegada, duração, distância),
    calculado pelo servidor a partir dos pontos;
  - quem abriu o mapa e quando (para responder a um pedido da LGPD).
- **A limpeza.** A T.I. apaga da API os pontos com mais de 7 dias e os deslocamentos com mais de 1 ano. O Nexus não usa
  nem mostra ponto com mais de 7 dias, mesmo antes da limpeza. O chat do Nexus deixa pronto o pedido à T.I. (o que
  apagar, de que livro, com que frequência), em texto para o dono mandar.
- **A tela.** O Mapa de equipe na torre Usinas 360°, no padrão dos mapas do Nexus: o desenho feito no servidor (o
  contorno do IBGE da Visão do responsável e do Mapa de risco), sem biblioteca de mapa e sem coordenada no navegador.
  Cada técnico é um ponto com a cor da situação: em deslocamento, parado numa usina, parado fora de usina, sem sinal há
  mais de 30 minutos, sem permissão. O clique abre ao lado: o técnico, a equipe, a hora da última posição, a usina mais
  perto e a distância ("a 2,3 km da UFV X"), e o **Abrir rota** para a usina escolhida.
- **Parado fora de usina** (a casa, o hotel): decisão do dono em 10/11/2026 (R13), no lugar da regra de 08/11 (só a
  cidade, sem o ponto exato): o mapa mostra a última posição de cada técnico dos últimos 7 dias no lugar exato, também
  parado fora de usina, e a de outro dia só com o contorno. Quem vê o mapa abre o **percurso** de um técnico num período
  desses 7 dias (o registro guarda quem abriu, quando e o período). O Abrir rota segue só para quem está em deslocamento
  ou parado numa usina.
- **Abrir rota sem a coordenada na página.** O botão leva a uma rota do Nexus que monta no servidor o link do Google
  Maps (origem no técnico, destino na usina) e redireciona. A página e o JSON nunca têm a coordenada; ela só sai no
  clique de quem pediu a rota, para o Google.
- **Quem vê.** Os setores PCM, COS, Performance, Supervisão Campo e Responsáveis UFV, pela tela de acessos do Nexus
  (Base → Acessos), e os administradores, que veem tudo por regra.

## 5. Dependências e ordem

1. **A regra escrita com RH e jurídico** (condição do dono em 21/10): o texto do termo de uso ao técnico e da guarda. O
   piloto pode começar antes, com o termo aprovado na prévia e só com técnicos que saibam.
2. **O Nexus primeiro:** as três rotas e o dado no banco (chat "Nexus (Usinas 360)"), para a casca ter onde mandar.
3. **A limpeza da T.I.** combinada antes de passar do piloto.
4. **A casca 1.2.0:** o termo de uso, a permissão, a coleta, a chave do aparelho e o guia atualizado.
5. **A tela** do Mapa de equipe (chat "Nexus (Usinas 360)").
6. **Piloto** com 1 ou 2 técnicos por uma semana, medindo a bateria e a cobertura do dia, antes de todos.

## 6. Riscos aceitos

- O Android pode adiar ou cortar a coleta em celular com economia de bateria agressiva, mesmo com os ajustes do guia: o
  mapa mostra "sem sinal há X min" e não esconde o buraco.
- O técnico que não abre o app no dia (nem toca no aviso das 6h) não aparece no mapa naquele dia. O aviso pode chegar
  alguns minutos depois das 6h com o celular em repouso; o celular desligado às 6h perde o aviso do dia; e o "Forçar
  parada" (ou o "tirar dos recentes" de quem mata o app, conforme a marca) apaga o alarme até a próxima abertura.
- Abrir rota manda ao Google a posição do técnico e a da usina, no clique de quem pediu.
- Entre os 7 dias e a limpeza da T.I., o ponto velho continua no banco, cifrado, sem uso.
- O ponto exato e o percurso mostram a casa ou o hotel de quem está parado fora de usina: risco aceito pelo dono em
  10/11/2026 (R13), com o aviso no termo da versão 2 e o registro de quem abriu o mapa e o percurso; o jurídico revê.
- Sem a notificação fixa à vista não há coleta: no Android 13 ou mais novo com a notificação do Nexus Field negada, ou com
  as notificações ou o canal da localização da equipe desligados nos ajustes, a coleta não liga (e para, se estava ligada).
  O mapa mostra esse aparelho como sem permissão.
- O técnico pode negar a localização (ou escolher a aproximada) no pedido do Android ou nos ajustes: o mapa mostra quem
  está sem permissão, e resolver isso é com o supervisor (o guia de instalação diz o caminho nos ajustes).
- O Android que mata o app (a falta de memória, o "tirar dos recentes" de quem mata o app) para a coleta até a próxima
  abertura: sem a localização "o tempo todo", o serviço não volta sozinho com o app no fundo (R10, aceito pelo dono; com
  ela seria igual do Android 12 em diante, que proíbe iniciar o serviço em primeiro plano em segundo plano).

## 7. O que fica para o construtor

- Como o serviço segue vivo até as 22h com o app fechado, e o que fazer se o Android o derrubar no meio do dia (voltar
  na próxima abertura, por exemplo).
- O intervalo exato parado e em deslocamento, a precisão pedida ao GPS e o tamanho dos lotes de envio.
- Quantos pontos o celular guarda sem sinal (até 24 h é a minha sugestão) e o que acontece se passar disso.
- No Nexus: como conferir o login Microsoft (a assinatura, o emissor e o público do token), o formato da chave e onde
  ela fica (só o hash no banco), quem conta como técnico de campo ativo no cadastro, o raio que conta como "na usina", a
  distância que conta como "fora de usina" e a cor de cada situação.

## 8. O contrato entre a casca e o Nexus (08/11/2026, com os ajustes do chat do Nexus)

Escrito depois da aprovação, para os dois lados construírem ao mesmo tempo. O chat "Nexus (Usinas 360)" pode mudar o
caminho e os nomes; o que mudar, ele avisa à sessão do Nexus Field antes de pôr no ar. Base: o endereço do Nexus no ar
(`https://app.gridco.com.br`, ou com o `NEXUS_PREFIXO` se ele voltar).

- **`POST /t/campo/equipe/aparelho`** (pública; chamada pela PÁGINA do App dentro do Nexus Field, porque só ela tem o
  login Microsoft, e o token Microsoft nunca vai ao nativo).
  - CORS: só a origem `https://gridco-campo-mw.azurewebsites.net`, com `Authorization` e `Content-Type`.
  - Pedido: `Authorization: Bearer <token do getTokenMW da página>`; corpo `{aparelho, app}`: `aparelho` é um id
    aleatório e estável que a casca cria uma vez por instalação (não é IMEI nem nada do aparelho); `app` é
    `NexusField/<versão>`.
  - O Nexus confere o token (assinatura pelas chaves públicas da Microsoft, emissor do tenant da Grid, público = o
    client id do App de Campo, validade; os dois ids estão no `msalConfig` do `campo.html`), acha a pessoa pelo e-mail no
    cadastro e decide se a coleta vale para ela (técnico de campo ativo; no piloto, só os escolhidos).
  - Resposta: `200 {coleta: true, chave}` (a chave: 32 bytes aleatórios em base64url; o banco guarda só o hash; um par
    pessoa + aparelho tem uma chave só, e a nova troca a velha) ou `200 {coleta: false}`; `401` com o token inválido ou
    vencido; `503` com a Microsoft fora do ar ou o servidor sem configuração (a casca não apaga a chave: só o `401`
    apaga).
  - A página só pede a chave quando o aparelho não tem uma, ou depois de um `401` (cada pedido gera chave nova e revoga
    a anterior do mesmo par): o nativo diz se tem chave (`temChave`).
- **`POST /t/campo/equipe/posicoes`** (pública; chamada pelo NATIVO, com o app aberto ou fechado).
  - Pedido: `Authorization: Bearer <chave>`; corpo `{aparelho, fuso, pontos: [{t, lat, lon, prec, vel, mov}]}`: `t` em
    milissegundos UTC, `prec` em metros, `vel` em m/s ou nulo, `mov` se a própria leitura está em deslocamento (1 m/s
    ou mais, ou 150 m ou mais da leitura anterior e além da precisão das duas; a primeira leitura sem velocidade vai
    `false`; é o ponto, e não o ritmo da coleta, que segue curto por três leituras depois de parar), `fuso` os minutos
    que se somam ao UTC para dar a hora do celular (Brasília -180, Mato Grosso -240: o `TimeZone.getOffset(t)/60000` do
    Android), para o Nexus conferir a janela das 6h às 22h na hora do técnico. Até 200 pontos e 64 KB por lote.
  - Sem a permissão de localização (a precisa, durante o uso) ou sem a notificação fixa à vista, o nativo manda
    `{aparelho, fuso, permissao: "sem", pontos: []}` (no máximo um a cada 30 minutos), para o mapa separar quem negou de
    quem não abriu o app. O formato é o mesmo de antes do termo de uso (R10), quando o "sem" era a falta da "o tempo
    todo".
  - O Nexus descarta ponto com mais de 7 dias ou fora da janela, cifra a coordenada e grava.
  - Resposta: `200 {aceitos, coleta}` (`coleta: false` faz a casca parar até a próxima carga); `401` com a chave
    inválida ou revogada (a casca apaga a chave e para); qualquer `5xx` é como sem rede (a fila e a chave ficam).
- **`POST /t/campo/equipe/aparelho/revogar`** (pública; chamada pelo NATIVO no Sair): `Authorization: Bearer <chave>`,
  corpo `{aparelho}`; `204`. A casca apaga a chave dela mesmo sem resposta.

## 9. Decisões que foram minhas (para o dono conferir)

- **Só o Google Maps no Abrir rota.** O link do Waze só recebe o destino e traça a rota a partir de quem clica: no
  escritório, ele sairia do escritório, e não do técnico.
- **Parado fora de usina sem o ponto exato e sem a rota**, para o mapa não mostrar onde o técnico mora. Superada pela
  decisão do dono de 10/11/2026 (R13): o ponto exato e o percurso aparecem, e o termo da versão 2 avisa.
- **Quem é coletado** decidido pelo Nexus na chave do aparelho (técnico de campo ativo; no piloto, só os escolhidos).
- **A hora do celular** para as 6h e as 22h (vale para técnico em outro fuso).
- O envio adaptativo: 2 minutos em deslocamento, mais espaçado parado.
- A chave própria do aparelho, pedida com o login da página e apagada no Sair.
- O desenho do mapa no padrão do Nexus, sem a coordenada na página, e o Abrir rota por um redirecionamento do servidor.
- "Sem sinal" a partir de 30 minutos sem posição.
- O registro de quem abriu o mapa.
