#!/usr/bin/env python3
"""
AgentK Prompt Crawler
Automates the submission of prompts to agentk.local and collects results.
Seguindo princípios SRP, Fail-Fast e Clean Code.
"""

import os
import sys
import time
import logging
from datetime import datetime
from pathlib import Path
from typing import List, Optional, Dict
# pyrefly: ignore [missing-import]
from playwright.sync_api import sync_playwright, Browser, Page, TimeoutError

# === CONFIGURAÇÃO (CONSTANTES) ===
SCRIPT_DIR = Path(__file__).parent.absolute()
ROOT_DIR = SCRIPT_DIR.parent

AGENTK_URL = os.getenv("AGENTK_URL", "https://agentk.local")
AGENTK_USER = os.getenv("AGENTK_USER", "efraim")
AGENTK_PASS = os.getenv("AGENTK_PASS", "633535")

# Caminhos robustos (baseados na localização do script)
PROMPTS_FILE = ROOT_DIR / "PROMPTS.md"
OUTPUT_DIR = SCRIPT_DIR / "output"
SCREENSHOTS_DIR = OUTPUT_DIR / "screenshots"

MAX_PROCESSING_WAIT_SEC = 200 # Cobre a cadeia completa: Ollama (120s) + long-poll gateway (150s) + overhead de UI
LOGIN_TIMEOUT_MS = 30000

# Fail-Fast: Garante que os diretórios de saída existam antes de iniciar o log
try:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    SCREENSHOTS_DIR.mkdir(parents=True, exist_ok=True)
except Exception as e:
    print(f"Erro fatal ao criar diretórios de saída: {e}")
    sys.exit(1)

# Configuração de Logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - [%(levelname)s] - %(message)s',
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler(OUTPUT_DIR / "crawler.log")
    ]
)
logger = logging.getLogger("AgentKCrawler")

class PromptLoader:
    """Classe responsável pelo carregamento e parsing dos prompts."""
    
    @staticmethod
    def load_from_markdown(file_path: Path) -> List[str]:
        if not file_path.exists():
            logger.error(f"Arquivo de prompts não encontrado: {file_path}")
            sys.exit(1)
            
        prompts = []
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                for line in f:
                    clean_line = line.strip()
                    # Ignora linhas vazias, headers markdown e comentários
                    if not clean_line or clean_line.startswith("#") or clean_line.startswith("<!--"):
                        continue
                    # Remove numeração se existir (ex: "1. Prompt")
                    if "." in clean_line[:4] and clean_line[0].isdigit():
                        parts = clean_line.split(".", 1)
                        if len(parts) > 1:
                            clean_line = parts[1].strip()
                    
                    prompts.append(clean_line)
            logger.info(f"Carregados {len(prompts)} prompts de {file_path}")
        except Exception as e:
            logger.error(f"Falha ao carregar prompts: {e}")
            sys.exit(1)
        return prompts

class AgentKAutomation:
    """Encapsula a lógica de automação do navegador para o AgentK."""
    
    def __init__(self, headless: bool = True):
        self.headless = headless
        self.playwright = None
        self.browser = None
        self.context = None
        self.page = None

    def __enter__(self):
        self.playwright = sync_playwright().start()
        self.browser = self.playwright.chromium.launch(headless=self.headless)
        # Ignora erros de SSL devido ao certificado auto-assinado do agentk.local
        self.context = self.browser.new_context(ignore_https_errors=True)
        self.page = self.context.new_page()
        logger.info("Sessão do navegador iniciada.")
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if self.browser:
            self.browser.close()
        if self.playwright:
            self.playwright.stop()
        logger.info("Sessão do navegador encerrada.")

    def login(self):
        """Executa o fluxo de autenticação via Keycloak."""
        logger.info(f"Navegando para {AGENTK_URL}...")
        try:
            self.page.goto(AGENTK_URL)
            
            # Aguarda e preenche campos do Keycloak
            self.page.wait_for_selector("#username", timeout=LOGIN_TIMEOUT_MS)
            self.page.fill("#username", AGENTK_USER)
            self.page.fill("#password", AGENTK_PASS)
            self.page.click("#kc-login")
            
            # Valida se entrou na aplicação Streamlit
            self.page.wait_for_selector("div[data-testid='stAppViewContainer']", timeout=LOGIN_TIMEOUT_MS)
            logger.info("Autenticação realizada com sucesso.")
        except TimeoutError:
            error_img = SCREENSHOTS_DIR / "auth_error.png"
            self.page.screenshot(path=str(error_img))
            logger.error(f"Timeout na autenticação. Screenshot salva em: {error_img}")
            sys.exit(1)

    def process_prompt(self, prompt: str, index: int) -> Optional[Dict]:
        """Envia um prompt, aguarda processamento e coleta evidências."""
        logger.info(f"Enviando prompt {index}: '{prompt[:60]}...'")
        
        try:
            input_selector = "textarea[data-testid='stChatInputTextArea']"
            
            # Aguarda o campo estar visível
            self.page.wait_for_selector(input_selector, state="visible")
            
            # Fail-Fast: Aguarda explicitamente o campo ser habilitado (Streamlit re-enabling UI)
            # O 'fill' do Playwright já tenta fazer isso, mas o loop garante rastreabilidade no log.
            wait_enabled_start = time.time()
            while self.page.is_disabled(input_selector):
                if time.time() - wait_enabled_start > 20: # 20s de tolerância para re-habilitação
                    raise TimeoutError(f"O campo de input {input_selector} permaneceu desabilitado após processamento anterior.")
                time.sleep(0.5)

            self.page.fill(input_selector, prompt)
            self.page.press(input_selector, "Enter")
            
            # 1. Aguarda o sinal de processamento iniciado (atributo removido)
            try:
                self.page.wait_for_function(
                    "() => !document.body.hasAttribute('data-agentk-ready')", 
                    timeout=5000
                )
                logger.info("Processamento iniciado (sinal detectado)...")
            except:
                pass 

            # 2. Aguarda o sinal de conclusão (atributo definido como 'true')
            # Este sinal é emitido pelo AgentK via Javascript ao final de cada execução.
            self.page.wait_for_function(
                "() => document.body.getAttribute('data-agentk-ready') === 'true'", 
                timeout=0
            )
            logger.info("Processamento concluído via sinal de prontidão.")
            
            # Captura de tela do resultado
            ts = datetime.now().strftime("%Y%m%d_%H%M%S")
            screenshot_path = SCREENSHOTS_DIR / f"evidencia_{index}_{ts}.png"
            self.page.screenshot(path=str(screenshot_path), full_page=True)
            
            # Extração de texto para análise posterior
            content = self.page.inner_text("div[data-testid='stAppViewContainer']")
            
            return {
                "id": index,
                "prompt": prompt,
                "screenshot": str(screenshot_path),
                "content": content
            }
        except Exception as e:
            logger.error(f"Erro ao processar prompt {index}: {e}")
            return None

def main():
    # Inicialização Fail-Fast
    OUTPUT_DIR.mkdir(exist_ok=True)
    SCREENSHOTS_DIR.mkdir(exist_ok=True)
    
    prompts = PromptLoader.load_from_markdown(PROMPTS_FILE)
    if not prompts:
        logger.warning("Nenhum prompt válido encontrado.")
        return

    logger.info("Iniciando automação do AgentK Crawler...")
    
    with AgentKAutomation(headless=True) as bot:
        bot.login()
        
        for i, prompt_text in enumerate(prompts, 1):
            result = bot.process_prompt(prompt_text, i)
            
            if result:
                # Salva o texto coletado em arquivo individual
                result_file = OUTPUT_DIR / f"resultado_{i}.txt"
                with open(result_file, "w", encoding="utf-8") as f:
                    f.write(f"--- PROMPT ---\n{result['prompt']}\n\n")
                    f.write(f"--- RESULTADO ---\n{result['content']}\n")
            
            # Estabilização entre prompts
            time.sleep(1)

    logger.info("Tarefa de crawling finalizada.")

if __name__ == "__main__":
    main()
