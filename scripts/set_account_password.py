"""One-time, user-operated Auth Admin password setup. Credentials stay in memory."""
import json
from pathlib import Path
import threading
import tkinter as tk
from tkinter import messagebox
from urllib.request import Request, urlopen
from urllib.error import HTTPError


def request_json(url, key, method, body):
    headers = {"apikey": key, "Content-Type": "application/json"}
    if not key.startswith("sb_secret_"):
        headers["Authorization"] = "Bearer " + key
    request = Request(url, json.dumps(body).encode(), headers, method=method)
    try:
        with urlopen(request, timeout=25) as response:
            return json.load(response)
    except HTTPError as error:
        # Do not echo server responses, request bodies, or credentials.
        raise RuntimeError(f"Supabase returned HTTP {error.code}. Check the project secret key.") from None


def set_password(project_url, user_id, email, key, password):
    result = request_json(project_url + "/auth/v1/admin/users/" + user_id,
                          key, "PUT", {"password": password})
    if result.get("id") != user_id:
        raise RuntimeError("Unexpected account response.")
    # The returned user confirms the same account was updated. No tokens persisted.
    return result


def main():
    context = json.loads((Path(__file__).resolve().parents[1] /
                          ".verification/password-setup-context.json").read_text())
    window = tk.Tk()
    window.title("JetMeal — задать пароль один раз")
    window.geometry("560x330")
    tk.Label(window, text="Аккаунт: " + context["email"]).pack(pady=12)
    tk.Label(window, text="Secret key текущего проекта Supabase (sb_secret_…):").pack()
    key_field = tk.Entry(window, show="•", width=65)
    key_field.pack(pady=6)
    tk.Label(window, text="Новый пароль для входа в JetMeal:").pack()
    password_field = tk.Entry(window, show="•", width=65)
    password_field.pack(pady=6)
    tk.Label(window, text="Ключ и пароль не записываются в файлы и не отправляются в чат.").pack(pady=8)

    def submit():
        key, password = key_field.get().strip(), password_field.get()
        if not key or len(password) < 8:
            messagebox.showerror("Проверьте поля", "Укажите ключ и пароль длиной от 8 символов.")
            return
        button.config(state="disabled")

        def worker():
            try:
                set_password(context["project_url"], context["user_id"], context["email"], key, password)
                window.after(0, success)
            except Exception as error:
                text = str(error) if isinstance(error, RuntimeError) else "Не удалось связаться с Supabase."
                window.after(0, lambda: failure(text))

        threading.Thread(target=worker, daemon=True).start()

    def success():
        key_field.delete(0, tk.END)
        password_field.delete(0, tk.END)
        messagebox.showinfo("Готово", "Пароль установлен. Войдите в JetMeal с вашим e-mail и этим паролем.")
        window.destroy()

    def failure(text):
        messagebox.showerror("Ошибка", text)
        button.config(state="normal")

    button = tk.Button(window, text="Установить пароль", command=submit)
    button.pack(pady=12)
    window.mainloop()


if __name__ == "__main__":
    main()
