FROM python:3.11-slim
WORKDIR /app
COPY intel/requirements.txt intel/requirements.txt
RUN pip install --no-cache-dir -r intel/requirements.txt
COPY intel/app intel/app
# For a live what-if run: the simulator, the event vocabulary it speaks, and the
# configurations it runs (feature 87). Nothing here is written to.
COPY sim sim
COPY vayora_events vayora_events
COPY experiments/configs experiments/configs
# An unprivileged user: intel holds no state and writes no files.
RUN useradd --system --no-create-home intel
USER intel
EXPOSE 8000
CMD ["uvicorn", "intel.app.main:app", "--host", "0.0.0.0", "--port", "8000"]
