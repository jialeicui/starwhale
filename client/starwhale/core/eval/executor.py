import os
import typing as t
from pathlib import Path

import yaml
from loguru import logger

from starwhale.utils import console, now_str, is_darwin, gen_uniq_version
from starwhale.consts import (
    CURRENT_FNAME,
    DefaultYAMLName,
    VERSION_PREFIX_CNT,
    DEFAULT_MANIFEST_NAME,
    CNTR_DEFAULT_PIP_CACHE_DIR,
)
from starwhale.base.uri import URI
from starwhale.utils.fs import ensure_dir, ensure_file
from starwhale.api.model import PipelineHandler
from starwhale.base.type import URIType, EvalTaskType, RunSubDirType
from starwhale.consts.env import SWEnv
from starwhale.utils.error import NoSupportError, FieldTypeOrValueError
from starwhale.utils.config import SWCliConfigMixed
from starwhale.utils.process import check_call
from starwhale.utils.progress import run_with_progress_bar
from starwhale.core.model.model import StandaloneModel
from starwhale.core.runtime.model import StandaloneRuntime

_CNTR_WORKDIR = "/opt/starwhale"
_STATUS = PipelineHandler.STATUS


class EvalExecutor:
    def __init__(
        self,
        model_uri: str,
        dataset_uris: t.List[str],
        runtime_uri: str,
        project_uri: URI,
        version: str = "",
        name: str = "",
        job_name: str = "default",
        desc: str = "",
        gencmd: bool = False,
        use_docker: bool = False,
    ) -> None:
        self.name = name
        self.job_name = job_name
        self.desc = desc
        self.model_uri = model_uri
        self.project_uri = project_uri
        self.dataset_uris = [
            URI(u, expected_type=URIType.DATASET) for u in dataset_uris
        ]

        self.runtime_uri = runtime_uri

        if runtime_uri:
            self.runtime: t.Optional[StandaloneRuntime] = StandaloneRuntime(
                URI(runtime_uri, expected_type=URIType.RUNTIME)
            )
            self.baseimage = self.runtime.store.get_docker_base_image()
        else:
            self.runtime = None
            self.baseimage = ""

        self.project_dir = Path(self.project_uri.real_request_uri)

        self.gencmd = gencmd
        self.use_docker = use_docker
        self._manifest: t.Dict[str, t.Any] = {"status": _STATUS.START}

        if not version:
            logger.info("[step:init]create eval job version...")
            self._version = gen_uniq_version(self.name)
            self._manifest["version"] = self._version
            # self._manifest["created_at"] = now_str()  # type
            logger.info(f"[step:init]eval job version is {self._version}")
        else:
            self._version = version

        self.sw_config = SWCliConfigMixed()

        self._workdir = Path()
        self._model_dir = Path()
        self._runtime_dir = Path()

        self._do_validate()

    def _do_validate(self) -> None:
        if self.use_docker:
            if not self.runtime_uri:
                raise FieldTypeOrValueError("runtime_uri is none")
            if is_darwin(arm=True):
                raise NoSupportError(
                    "use docker as the evaluation job environment in MacOSX system (Apple Silicon processor)"
                )

    def __str__(self) -> str:
        return f"Evaluation Executor: {self.name}"

    def __repr__(self) -> str:
        return f"Evaluation Executor: name -> {self.name}, version -> {self._version}"

    def run(
        self, typ: str = EvalTaskType.ALL, step: str = "", task_index: int = 0
    ) -> str:
        try:
            self._do_run(typ, step, task_index)
        except Exception as e:
            self._manifest["status"] = _STATUS.FAILED
            self._manifest["error_message"] = str(e)
            raise
        finally:
            self._render_manifest()

        return self._version

    def _do_run(self, typ: str, step: str, task_index: int) -> None:
        self._manifest["type"] = typ
        self._manifest["status"] = _STATUS.RUNNING
        if typ != EvalTaskType.ALL:
            if not step:
                raise FieldTypeOrValueError("step is none")
            self._manifest["step"] = step
            self._manifest["task_index"] = task_index

        operations = [
            (self._prepare_workdir, 5, "prepare workdir"),
            (self._extract_swmp, 15, "extract model"),
            (self._extract_swrt, 15, "extract runtime"),
            (self._do_run_eval_job, 70, "run eval job"),
        ]

        run_with_progress_bar("eval run in local...", operations)

    def _do_run_eval_job(self) -> None:
        _type = self._manifest["type"]
        if _type != EvalTaskType.ALL:
            _step = self._manifest["step"]
            _task_index = self._manifest["task_index"]
            self._do_run_cmd(_type, _step, _task_index)
        else:
            self._do_run_cmd(_type, "", 0)

    def _prepare_workdir(self) -> None:
        logger.info("[step:prepare]create eval workdir...")
        # TODO: fix _workdir sequence-dependency issue
        self._workdir = (
            self.project_dir
            / URIType.EVALUATION
            / self._version[:VERSION_PREFIX_CNT]
            / self._version
        )

        ensure_dir(self._workdir)
        for _w in (self._workdir,):
            for _n in (RunSubDirType.SWMP,):
                ensure_dir(_w / _n)

        logger.info(f"[step:prepare]eval workdir: {self._workdir}")

    def _extract_swmp(self) -> None:
        _workdir = Path(self.model_uri)
        _model_yaml_path = _workdir / DefaultYAMLName.MODEL

        if _workdir.exists() and _model_yaml_path.exists() and not self.use_docker:
            self._model_dir = _workdir
        else:
            console.print("start to uncompress swmp...")
            model_uri = URI(self.model_uri, expected_type=URIType.MODEL)
            _m = StandaloneModel(model_uri)
            self._model_dir = _m.extract() / "src"

    def _extract_swrt(self) -> None:
        if self.runtime and self.use_docker:
            self._runtime_dir = self.runtime.extract()
        else:
            self._runtime_dir = Path()

    def _do_run_cmd(self, typ: str, step: str, task_index: int) -> None:
        if self.use_docker:
            self._do_run_cmd_in_container(typ, step, task_index)
        else:
            self._do_run_cmd_in_host(typ, step, task_index)

    def _do_run_cmd_in_host(self, typ: str, step: str, task_index: int) -> None:
        StandaloneModel.eval_user_handler(
            project=self.project_uri.project,
            version=self._version,
            typ=typ,
            src_dir=self._model_dir,
            workdir=self._workdir,
            dataset_uris=[u.full_uri for u in self.dataset_uris],
            step=step,
            task_index=task_index,
            kw={},
        )

    def _do_run_cmd_in_container(self, typ: str, step: str, task_index: int) -> None:
        cmd = self._gen_run_container_cmd(typ, step, task_index)
        console.rule(f":elephant: {typ} docker cmd", align="left")
        console.print(f"{cmd}\n")
        console.print(
            f":fish: eval run:{typ} dir @ [green blink]{self._workdir}/{typ}[/]"
        )
        if not self.gencmd:
            check_call(f"docker pull {self.baseimage}", shell=True)
            check_call(cmd, shell=True)

    def _gen_run_container_cmd(self, typ: str, step: str, task_index: int) -> str:
        if typ not in (EvalTaskType.ALL, EvalTaskType.SINGLE):
            raise Exception(f"no support {typ} to gen docker cmd")
        _entrypoint = "run_all"
        _run_dir = self._workdir

        cmd = [
            "docker",
            "run",
            "--net=host",
            "--rm",
            "--name",
            f"{self._version}-{step}-{task_index}",
            "-e",
            "DEBUG=1",
        ]

        cmd += [
            "-v",
            f"{_run_dir}:{_CNTR_WORKDIR}",
            "-v",
            f"{self.project_dir/URIType.DATASET}:/root/.starwhale/{self.project_uri.project}/{RunSubDirType.DATASET}",
            "-v",
            f"{self.sw_config.datastore_dir}:/root/.starwhale/.datastore",
            "-v",
            f"{self._model_dir}:{_CNTR_WORKDIR}/{RunSubDirType.SWMP}/src",
            "-v",
            f"{self._model_dir}/{DefaultYAMLName.MODEL}:{_CNTR_WORKDIR}/{RunSubDirType.SWMP}/{DefaultYAMLName.MODEL}",
            "-v",
            f"{self._runtime_dir}/dep:{_CNTR_WORKDIR}/{RunSubDirType.SWMP}/dep",
            "-v",
            f"{self._runtime_dir}/{DEFAULT_MANIFEST_NAME}:{_CNTR_WORKDIR}/{RunSubDirType.SWMP}/{DEFAULT_MANIFEST_NAME}",
        ]

        if typ == EvalTaskType.SINGLE:
            _entrypoint = "run_single"
            cmd.extend(["-e", f"SW_TASK_STEP={step}"])
            cmd.extend(["-e", f"SW_TASK_INDEX={task_index}"])

        cmd.extend(
            [
                "-e",
                f"{SWEnv.instance_uri}={self.sw_config._current_instance_obj['uri']}",
            ]
        )
        cmd.extend(["-e", f"{SWEnv.project}={self.project_uri.project}"])
        cmd.extend(["-e", f"{SWEnv.eval_version}={self._version}"])
        # TODO: support multi dataset
        cmd.extend(["-e", f"{SWEnv.dataset_uri}={self.dataset_uris[0]}"])

        cntr_cache_dir = os.environ.get("SW_PIP_CACHE_DIR", CNTR_DEFAULT_PIP_CACHE_DIR)
        host_cache_dir = os.path.expanduser("~/.cache/starwhale-pip")
        cmd += ["-v", f"{host_cache_dir}:{cntr_cache_dir}"]

        _env = os.environ
        for _ee in (
            "SW_PYPI_INDEX_URL",
            "SW_PYPI_EXTRA_INDEX_URL",
            "SW_PYPI_TRUSTED_HOST",
        ):
            if _ee not in _env:
                continue
            cmd.extend(["-e", f"{_ee}={_env[_ee]}"])

        cmd += [self.baseimage, _entrypoint]
        return " ".join(cmd)

    def _render_manifest(self) -> None:
        _status = True
        for _d in (self._workdir,):
            _f = _d / RunSubDirType.STATUS / CURRENT_FNAME
            if not _f.exists():
                continue
            _status = _status and (_f.open().read().strip() == _STATUS.SUCCESS)

        self._manifest.update(
            dict(
                name=self.name,
                desc=self.desc,
                model=self.model_uri,
                model_dir=str(self._model_dir),
                datasets=[u.full_uri for u in self.dataset_uris],
                runtime=self.runtime_uri,
                status=_STATUS.SUCCESS if _status else _STATUS.FAILED,
                finished_at=now_str(),
            )
        )
        _f = self._workdir / DEFAULT_MANIFEST_NAME
        ensure_file(_f, yaml.safe_dump(self._manifest, default_flow_style=False))