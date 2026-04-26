---
name: regras-globais
description: Describe what this skill does and when to use it. Include keywords that help agents identify relevant tasks.
---

<!-- Tip: Use /create-skill in chat to generate content with agent assistance -->

## Etapas para o desenvolvimento de quaisquer projetos de forma Global

Inicie qualquer tarefa lendo o arquivo ARCHITECTURE.md presente na raiz do diretório. Caso as instruções se tornem ambíguas ou a janela de contexto atinja seu limite funcional, descarte o histórico de conversação secundário e recarregue os parâmetros técnicos descritos exclusivamente neste arquivo. Caso não exista um arquivo ARCHITECTURE.md faça uma verredura nos demais arquivos .md na raiz do workspace e nos arquivos existentes no diretório e crie um arquivo ARCHITECTURE.md na raiz do diretório.

Utilize exclusivamente os métodos e bibliotecas confirmados na base de código atual. Interrompa o processamento e solicite documentação adicional ao usuário caso a solução demande ferramentas, APIs ou funções que não estão explicitamente declaradas.

Forneça saídas compostas exclusivamente por código funcional. Omita explicações teóricas, a menos que sejam solicitadas. Retorne apenas as funções ou blocos lógicos modificados. Evite comentar o código de forma desnecessária.

Implemente o padrão de projeto 'Fail-Fast'. Valide os argumentos de entrada no início de toda função. Envolva operações de I/O ou chamadas de rede em blocos de tratamento de erros (try-catch). Substitua literais de texto e números mágicos por constantes tipadas ou variáveis de ambiente.

Construa o código orientando-se pelo Princípio da Responsabilidade Única (SRP). Extraia rotinas lógicas complexas para funções puras auxiliares. Aplique o padrão de injeção de dependências em vez de instanciar classes ou serviços internos dentro de funções operacionais.

Sempre que gerar alterações no código (criando arquivos, editando arquivos), adicione o que foi alterado no arquivo CHANGES.md em escrita acadêmica. Inclua o nome do arquivo, a data da alteração, uma descrição detalhada da mudança e o motivo da alteração. Mantenha um registro claro e organizado de todas as modificações para facilitar a rastreabilidade e a revisão futura.

Define the functionality provided by this skill, including detailed instructions and examples