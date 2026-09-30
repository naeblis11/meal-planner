"""Console helper for the installer's prompts, testable."""
import getpass


class Console:
    """Prompts and prints for a guided installer, delegating I/O for testing."""

    def __init__(self, input_fn=input, secret_fn=getpass.getpass, output_fn=print):
        self.input_fn = input_fn
        self.secret_fn = secret_fn
        self.output_fn = output_fn

    def say(self, text=""):
        """Print text (or blank line if text is empty)."""
        self.output_fn(text)

    def heading(self, text):
        """Print blank line, text, then underline of '-' same length as text."""
        self.output_fn()
        self.output_fn(text)
        self.output_fn("-" * len(text))

    def ask(self, prompt, default=""):
        """Prompt for input. Returns default if blank. Strips whitespace."""
        if default:
            full_prompt = f"{prompt} [{default}]: "
        else:
            full_prompt = f"{prompt}: "
        answer = self.input_fn(full_prompt).strip()
        return answer or default

    def ask_secret(self, prompt):
        """Prompt for secret input (password). Returns result exactly."""
        answer = self.secret_fn(prompt)
        return answer

    def yes_no(self, prompt, default):
        """Prompt for yes/no. Default is a bool. Returns bool."""
        while True:
            if default:
                full_prompt = f"{prompt} [Y/n]: "
            else:
                full_prompt = f"{prompt} [y/N]: "
            answer = self.input_fn(full_prompt).strip().lower()
            if not answer:
                return default
            if answer in ("y", "yes"):
                return True
            if answer in ("n", "no"):
                return False
            self.output_fn("Please answer y or n.")

    def choose(self, prompt, options, default=1):
        """Prompt to choose from numbered list. Returns 1-based index."""
        self.output_fn(prompt)
        for i, option in enumerate(options, 1):
            self.output_fn(f"  {i}) {option}")

        while True:
            full_prompt = f"Choose 1-{len(options)} [{default}]: "
            answer = self.input_fn(full_prompt).strip()
            if not answer:
                return default
            try:
                choice = int(answer)
                if 1 <= choice <= len(options):
                    return choice
            except ValueError:
                pass
            self.output_fn(f"Enter a number from 1 to {len(options)}.")

    def pause(self, prompt="Press Enter when you've done that..."):
        """Wait for user to press Enter."""
        self.input_fn(prompt)
