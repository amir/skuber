package skuber.examples.alpha.controller

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import play.api.libs.json.Format
import shapeless._
import skuber.LabelSelector.IsEqualRequirement
import skuber._
import skuber.json.format._
import skuber.json.ext.format._
import skuber.api.alpha.controller.ListWatch.ListWatchers
import skuber.api.client.LoggingContext
import skuber.ext.Deployment

import scala.concurrent.Future

object WithTypeCase extends App {

  import skuber.api.client.EventType

  implicit val system = ActorSystem()
  implicit val dispatcher = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val k8s = k8sInit

  val `K8SWatchEvent[Pod]` = TypeCase[K8SWatchEvent[Pod]]
  val `K8SWatchEvent[Deployment]` = TypeCase[K8SWatchEvent[Deployment]]
  val `K8SWatchEvent[Foo]` = TypeCase[K8SWatchEvent[Foo]]

  val podsListWatcher = new ListWatchers[Pod](k8s)
  val deploymentsListWatcher = new ListWatchers[Deployment](k8s)
  val foosListWatcher = new ListWatchers[Foo](k8s)

  def getInNamespaceOption[O <: ObjectResource](name: String, namespace: String)(
                                               implicit fmt: Format[O], rd: ResourceDefinition[O], lc: LoggingContext
  ): Future[Option[O]] = {
    k8s.getInNamespace[O](name, namespace).map { result =>
      Some(result)
    } recover {
      case ex: K8SException if ex.status.code.contains(StatusCodes.NotFound.intValue) => None
    }
  }

  def createDeployment(f: Foo): Future[Deployment] = {
    val labels = Map(
      "app" -> "nginx",
      "controller" -> f.metadata.name
    )

    val container = Container(name = "nginx", image="nginx:latest")

    val template = Pod.Template.Spec(metadata=ObjectMeta()).addLabels(labels).addContainer(container)

    val deployment = Deployment(metadata = ObjectMeta(
      name = f.spec.fold("foo")(_.deploymentName),
      namespace = f.metadata.namespace,
      ownerReferences = List(OwnerReference(
        apiVersion = f.apiVersion, kind = f.kind, name = f.metadata.name, uid = "1", controller = None, blockOwnerDeletion = None
      ))
    )).withReplicas(f.spec.fold(1)(_.replicas))
      .withLabelSelector(LabelSelector(labels.map(x => IsEqualRequirement(x._1, x._2)).toList :_*))
      .withTemplate(template)

    k8s.create[Deployment](deployment)
  }

  def updateDeployment(f: Foo, deployment: Deployment): Future[Deployment] = {
    val updated = f.spec.fold(deployment) { spec =>
      deployment.withResourceVersion(deployment.metadata.resourceVersion)
        .copy(metadata = deployment.metadata.copy(name=spec.deploymentName))
        .withReplicas(spec.replicas)
    }

    k8s.update[Deployment](updated)
  }

  def reconcile[O <: ObjectResource](o: K8SWatchEvent[O]): ObjectResource = {
    o match {
      case `K8SWatchEvent[Foo]`(we) =>
        we._object.spec.foreach { spec =>
          getInNamespaceOption[Deployment](name = spec.deploymentName, namespace = we._object.metadata.namespace).map {
            case Some(d) =>
              updateDeployment(we._object, d)
            case None =>
              createDeployment(we._object)
          }
        }
        we._object
      case `K8SWatchEvent[Deployment]`(we) => we._object
    }
  }

  podsListWatcher.listWatchAll.watch(None)
    .merge(deploymentsListWatcher.listWatchAll.watch(None))
      .merge(foosListWatcher.listWatchAll.watch(None)
  ).map { we =>
    we._type match {
      case EventType.ADDED => reconcile(we)
      case EventType.MODIFIED => reconcile(we)
      case EventType.DELETED => we
      case EventType.ERROR => we
    }
  }.runWith(Sink.foreach(println))
}