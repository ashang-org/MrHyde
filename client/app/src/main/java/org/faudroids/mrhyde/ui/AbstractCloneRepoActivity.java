package org.faudroids.mrhyde.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.google.common.base.Optional;
import com.karumi.dexter.Dexter;
import com.karumi.dexter.listener.PermissionGrantedResponse;
import com.karumi.dexter.listener.single.EmptyPermissionListener;

import org.faudroids.mrhyde.R;
import org.faudroids.mrhyde.git.CloneRepositoryService;
import org.faudroids.mrhyde.git.RepositoriesManager;
import org.faudroids.mrhyde.git.Repository;
import org.faudroids.mrhyde.ui.utils.AbstractActivity;
import org.faudroids.mrhyde.utils.AbstractErrorAction;
import org.faudroids.mrhyde.utils.DefaultErrorAction;
import org.faudroids.mrhyde.utils.DefaultTransformer;
import org.faudroids.mrhyde.utils.ErrorActionBuilder;
import org.faudroids.mrhyde.utils.HideSpinnerAction;

import java.util.Collection;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import rx.Observable;

import static org.faudroids.mrhyde.git.CloneRepositoryService.CloneStatusBinder;
import static org.faudroids.mrhyde.git.CloneRepositoryService.EXTRA_IMPORT_PRE_V1_REPOSITORY;
import static org.faudroids.mrhyde.git.CloneRepositoryService.EXTRA_NOTIFICATION_TARGET_ACTIVITY;
import static org.faudroids.mrhyde.git.CloneRepositoryService.EXTRA_REPOSITORY;

/**
 * Shows a list of available repos which option to clone a single repo.
 */
abstract class AbstractCloneRepoActivity
    extends AbstractActivity
    implements RepositoryRecyclerViewAdapter.RepositorySelectionListener {

  @Inject protected RepositoriesManager repositoriesManager;
  @Inject protected ActivityIntentFactory intentFactory;

  @BindView(R.id.list) protected RecyclerView recyclerView;
  protected RepositoryRecyclerViewAdapter repoAdapter;

  private Optional<CloneStatusConnection> cloneStatusConnection = Optional.absent();

  abstract Observable<Collection<Repository>> getAllRemoteRepositories();

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_clone_repo);
    ButterKnife.bind(this);
    setTitle(R.string.title_clone_new_repo);

    // setup list
    RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(this);
    recyclerView.setLayoutManager(layoutManager);
    repoAdapter = new RepositoryRecyclerViewAdapter(this, this);
    recyclerView.setAdapter(repoAdapter);
    loadRepositories();
  }

  private void loadRepositories() {
    showSpinner();
    // show only repos that have not yet been cloned
    compositeSubscription.add(Observable
        .zip(
            getAllRemoteRepositories(),
            repositoriesManager.getClonedRepositories(),
            (gitHubRepos, clonedRepos) -> Observable
                .from(gitHubRepos)
                .filter(repo -> !clonedRepos.contains(repo))
                .toList()
                .toBlocking().first()
        )
        .compose(new DefaultTransformer<>())
        .subscribe(repositories -> {
          // only disable spinner if not waiting for clone
          if (!cloneStatusConnection.isPresent()) hideSpinner();
          repoAdapter.setItems(repositories);
        }, new ErrorActionBuilder()
            .add(new DefaultErrorAction(this, "failed to get repos"))
            .add(new HideSpinnerAction(this))
            .build()));
  }

  @Override
  public void onRepositorySelected(Repository repository) {
    // assert has required permission
    Dexter.checkPermission(new EmptyPermissionListener() {
      @Override
      public void onPermissionGranted(PermissionGrantedResponse r) {
        cloneRepository(repository);
      }
    }, Manifest.permission.WRITE_EXTERNAL_STORAGE);
  }

  private void cloneRepository(Repository repository) {
    // check for pre v1 repos that can be imported
    if (repositoriesManager.canPreV1RepoBeImported(repository)) {
      new MaterialDialog
          .Builder(this)
          .title(R.string.import_repo_title)
          .content(R.string.import_repo_message)
          .positiveText(R.string.import_repo_confirm)
          .negativeText(android.R.string.cancel)
          .checkBoxPromptRes(R.string.import_repo_check_import, true, null)
          .onPositive((dialog, which) -> {
            boolean importRepo = dialog.isPromptCheckBoxChecked();
            cloneRepository(repository, importRepo);
          })
          .show();
      return;
    }

    // clone repo
    new MaterialDialog
        .Builder(this)
        .title(R.string.clone_repo_title)
        .content(R.string.clone_repo_message)
        .positiveText(R.string.clone_repo_confirm)
        .negativeText(android.R.string.cancel)
        .onPositive((dialog, which) -> cloneRepository(repository, false))
        .show();
  }

  private void cloneRepository(Repository repository, boolean importPreV1Repo) {
    Intent cloneIntent = new Intent(this, CloneRepositoryService.class);
    cloneIntent.putExtra(EXTRA_REPOSITORY, repository);
    cloneIntent.putExtra(EXTRA_IMPORT_PRE_V1_REPOSITORY, importPreV1Repo);
    cloneIntent.putExtra(EXTRA_NOTIFICATION_TARGET_ACTIVITY, getClass());
    startService(cloneIntent);
    bindToCloneService();
  }

  private void bindToCloneService() {
    showSpinner();
    cloneStatusConnection = Optional.of(new CloneStatusConnection());
    bindService(
        new Intent(this, CloneRepositoryService.class),
        cloneStatusConnection.get(),
        Context.BIND_AUTO_CREATE
    );
  }

  private void unbindFromCloneService() {
    unbindService(cloneStatusConnection.get());
    cloneStatusConnection = Optional.absent();
  }

  @Override
  public void onStart() {
    if (CloneRepositoryService.isRunning()) {
      bindToCloneService();
    }
    super.onStart();
  }

  @Override
  public void onStop() {
    if (cloneStatusConnection.isPresent()) {
      unbindFromCloneService();
    }
    super.onStop();
  }

  /**
   * Tracks (un-) binding of the {@link CloneRepositoryService} class.
   */
  private class CloneStatusConnection implements ServiceConnection {

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder binder) {
      CloneStatusBinder statusBinder = (CloneStatusBinder) binder;
      compositeSubscription.add(statusBinder
          .getCloneStatus()
          .subscribe(
              gitManager -> {
                // open overview
                startActivity(intentFactory.createRepoOverviewIntent(statusBinder.getRepository()));
                finish();
              },
              new ErrorActionBuilder()
                  .add(new DefaultErrorAction(AbstractCloneRepoActivity.this, "Failed to clone repo"))
                  .add(new HideSpinnerAction(AbstractCloneRepoActivity.this))
                  .add(new AbstractErrorAction() {
                    @Override
                    protected void doCall(Throwable throwable) {
                      unbindFromCloneService();
                    }
                  })
                  .build()
          )
      );
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
      // nothing to do
    }

  }

}
